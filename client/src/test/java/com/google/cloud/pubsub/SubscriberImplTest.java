/*
 * Copyright 2016 Google Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.cloud.pubsub;

import static org.junit.Assert.assertEquals;

import com.google.cloud.pubsub.FakeSubscriberServiceImpl.ModifyAckDeadline;
import com.google.cloud.pubsub.Subscriber.Builder;
import com.google.cloud.pubsub.Subscriber.MessageReceiver;
import com.google.cloud.pubsub.Subscriber.MessageReceiver.AckReply;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.Service.State;
import com.google.common.util.concurrent.SettableFuture;
import com.google.pubsub.v1.PubsubMessage;
import com.google.pubsub.v1.PullResponse;
import com.google.pubsub.v1.ReceivedMessage;
import com.google.pubsub.v1.StreamingPullResponse;
import io.grpc.Status;
import io.grpc.StatusException;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.internal.ServerImpl;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.joda.time.Duration;
import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

/** Tests for {@link SubscriberImpl}. */
@RunWith(Parameterized.class)
public class SubscriberImplTest {

  private static final String TEST_SUBSCRIPTION =
      "projects/test-project/subscriptions/test-subscription";

  private static final PubsubMessage TEST_MESSAGE =
      PubsubMessage.newBuilder().setMessageId("1").build();

  @Parameters
  public static Collection<Object[]> data() {
    return Arrays.asList(new Object[][] {{true}, {false}});
  }

  private final boolean isStreamingTest;

  private InProcessChannelBuilder testChannelBuilder;
  private FakeScheduledExecutorService fakeExecutor;
  private FakeSubscriberServiceImpl fakeSubscriberServiceImpl;
  private ServerImpl testServer;

  private FakeCredentials testCredentials;
  private TestReceiver testReceiver;

  static class TestReceiver implements MessageReceiver {
    private final Deque<SettableFuture<AckReply>> outstandingMessageReplies =
        new ConcurrentLinkedDeque<>();
    private AckReply ackReply = AckReply.ACK;
    private Optional<CountDownLatch> messageCountLatch = Optional.absent();
    private Optional<Throwable> error = Optional.absent();
    private boolean explicitAckReplies;

    void setReply(AckReply ackReply) {
      this.ackReply = ackReply;
    }

    void setErrorReply(Throwable error) {
      this.error = Optional.of(error);
    }

    void setExplicitAck(boolean explicitAckReplies) {
      this.explicitAckReplies = explicitAckReplies;
    }

    void setExpectedMessages(int expected) {
      this.messageCountLatch = Optional.of(new CountDownLatch(expected));
    }

    void waitForExpectedMessages() throws InterruptedException {
      if (messageCountLatch.isPresent()) {
        messageCountLatch.get().await();
      }
    }

    @Override
    public ListenableFuture<AckReply> receiveMessage(PubsubMessage message) {
      if (messageCountLatch.isPresent()) {
        messageCountLatch.get().countDown();
      }
      SettableFuture<AckReply> reply = SettableFuture.create();

      if (explicitAckReplies) {
        outstandingMessageReplies.add(reply);
      } else {
        if (error.isPresent()) {
          reply.setException(error.get());
        } else {
          reply.set(ackReply);
        }
      }

      return reply;
    }

    public void replyNextOutstandingMessage() {
      Preconditions.checkState(explicitAckReplies);

      SettableFuture<AckReply> reply = outstandingMessageReplies.poll();
      if (error.isPresent()) {
        reply.setException(error.get());
      } else {
        reply.set(ackReply);
      }
    }

    public void replyAllOutstandingMessage() {
      Preconditions.checkState(explicitAckReplies);

      while (!outstandingMessageReplies.isEmpty()) {
        replyNextOutstandingMessage();
      }
    }
  }

  public SubscriberImplTest(boolean streamingTest) {
    this.isStreamingTest = streamingTest;
  }

  @Rule public TestName testName = new TestName();

  @Before
  public void setUp() throws Exception {
    MockitoAnnotations.initMocks(this);

    InProcessServerBuilder serverBuilder = InProcessServerBuilder.forName(testName.getMethodName());
    fakeSubscriberServiceImpl = Mockito.spy(new FakeSubscriberServiceImpl());
    fakeExecutor = new FakeScheduledExecutorService();
    testChannelBuilder = InProcessChannelBuilder.forName(testName.getMethodName());
    serverBuilder.addService(fakeSubscriberServiceImpl);
    testServer = serverBuilder.build();
    testServer.start();

    testReceiver = new TestReceiver();
    testCredentials = new FakeCredentials();
  }

  @After
  public void tearDown() throws Exception {
    testServer.shutdownNow().awaitTermination();
    fakeSubscriberServiceImpl.reset();
  }

  @Test
  public void testAckSingleMessage() throws Exception {
    Subscriber subscriber = startSubscriber(getTestSubscriberBuilder(testReceiver));

    List<String> testAckIds = ImmutableList.of("A");
    sendMessages(testAckIds);

    // Trigger ack sending
    subscriber.stopAsync().awaitTerminated();

    assertEquivalent(testAckIds, fakeSubscriberServiceImpl.waitAndConsumeReceivedAcks(1));
  }

  @Test
  public void testNackSingleMessage() throws Exception {
    Subscriber subscriber = startSubscriber(getTestSubscriberBuilder(testReceiver));

    testReceiver.setReply(AckReply.NACK);
    sendMessages(ImmutableList.of("A"));

    // Trigger ack sending
    subscriber.stopAsync().awaitTerminated();

    assertEquivalent(
        ImmutableList.of(new ModifyAckDeadline("A", 0)),
        fakeSubscriberServiceImpl.waitAndConsumeModifyAckDeadlines(1));
  }

  @Test
  public void testReceiverError_NacksMessage() throws Exception {
    testReceiver.setErrorReply(new Exception("Can't process message"));

    Subscriber subscriber = startSubscriber(getTestSubscriberBuilder(testReceiver));

    sendMessages(ImmutableList.of("A"));

    // Trigger nack sending
    subscriber.stopAsync().awaitTerminated();

    assertEquivalent(
        ImmutableList.of(new ModifyAckDeadline("A", 0)),
        fakeSubscriberServiceImpl.getModifyAckDeadlines());
  }

  @Test
  public void testBatchAcks() throws Exception {
    Subscriber subscriber = startSubscriber(getTestSubscriberBuilder(testReceiver));

    List<String> testAckIdsBatch1 = ImmutableList.of("A", "B", "C");
    sendMessages(testAckIdsBatch1);

    // Trigger ack sending
    fakeExecutor.advanceTime(StreamingSubscriberConnection.PENDING_ACKS_SEND_DELAY);

    assertEquivalent(testAckIdsBatch1, fakeSubscriberServiceImpl.waitAndConsumeReceivedAcks(3));

    // Ensures the next ack sending alarm gets properly setup
    List<String> testAckIdsBatch2 = ImmutableList.of("D", "E");
    sendMessages(testAckIdsBatch2);

    fakeExecutor.advanceTime(StreamingSubscriberConnection.PENDING_ACKS_SEND_DELAY);

    assertEquivalent(testAckIdsBatch2, fakeSubscriberServiceImpl.waitAndConsumeReceivedAcks(2));

    subscriber.stopAsync().awaitTerminated();
  }

  @Test
  public void testBatchAcksAndNacks() throws Exception {
    Subscriber subscriber = startSubscriber(getTestSubscriberBuilder(testReceiver));

    // Send messages to be acked
    List<String> testAckIdsBatch1 = ImmutableList.of("A", "B", "C");
    sendMessages(testAckIdsBatch1);

    // Send messages to be nacked
    List<String> testAckIdsBatch2 = ImmutableList.of("D", "E");
    // Nack messages
    testReceiver.setReply(AckReply.NACK);
    sendMessages(testAckIdsBatch2);

    // Trigger ack sending
    fakeExecutor.advanceTime(StreamingSubscriberConnection.PENDING_ACKS_SEND_DELAY);

    assertEquivalent(testAckIdsBatch1, fakeSubscriberServiceImpl.waitAndConsumeReceivedAcks(3));
    assertEquivalent(
        ImmutableList.of(new ModifyAckDeadline("D", 0), new ModifyAckDeadline("E", 0)),
        fakeSubscriberServiceImpl.waitAndConsumeModifyAckDeadlines(2));

    subscriber.stopAsync().awaitTerminated();
  }

  @Test
  public void testModifyAckDeadline() throws Exception {
    Subscriber subscriber =
        startSubscriber(
            getTestSubscriberBuilder(testReceiver)
                .setAckExpirationPadding(Duration.standardSeconds(1)));

    // Send messages to be acked
    List<String> testAckIdsBatch = ImmutableList.of("A", "B", "C");
    testReceiver.setExplicitAck(true);
    sendMessages(testAckIdsBatch);

    // Trigger modify ack deadline sending - 10s initial stream ack deadline - 1 padding
    fakeExecutor.advanceTime(Duration.standardSeconds(9));

    assertEquivalentWithTransformation(
        testAckIdsBatch,
        fakeSubscriberServiceImpl.waitAndConsumeModifyAckDeadlines(3),
        new Function<String, ModifyAckDeadline>() {
          @Override
          public ModifyAckDeadline apply(String ack) {
            return new ModifyAckDeadline(ack, 2); // 2 seconds is the initial mod ack deadline
          }
        });

    // Trigger modify ack deadline sending - 2s of the renewed deadlines
    fakeExecutor.advanceTime(Duration.standardSeconds(2));

    assertEquivalentWithTransformation(
        testAckIdsBatch,
        fakeSubscriberServiceImpl.waitAndConsumeModifyAckDeadlines(3),
        new Function<String, ModifyAckDeadline>() {
          @Override
          public ModifyAckDeadline apply(String ack) {
            return new ModifyAckDeadline(ack, 4);
          }
        });

    testReceiver.replyAllOutstandingMessage();
    subscriber.stopAsync().awaitTerminated();
  }

  @Test
  public void testStreamAckDeadlineUpdate() throws Exception {
    if (!isStreamingTest) {
      // This test is not applicable to polling.
      return;
    }

    Subscriber subscriber =
        startSubscriber(
            getTestSubscriberBuilder(testReceiver)
                .setAckExpirationPadding(Duration.standardSeconds(1)));

    fakeSubscriberServiceImpl.waitForStreamAckDeadline(10);

    // Send messages to be acked
    testReceiver.setExplicitAck(true);
    sendMessages(ImmutableList.of("A"));

    // Make the ack latency of the receiver equals 20 seconds
    fakeExecutor.advanceTime(Duration.standardSeconds(20));
    testReceiver.replyNextOutstandingMessage();

    // Wait for an ack deadline update
    fakeExecutor.advanceTime(Duration.standardSeconds(60));

    fakeSubscriberServiceImpl.waitForStreamAckDeadline(20);

    // Send more messages to be acked
    testReceiver.setExplicitAck(true);
    for (int i = 0; i < 999; i++) {
      sendMessages(ImmutableList.of(Integer.toString(i)));
    }

    // Reduce the 99th% ack latency of the receiver to 10 seconds
    fakeExecutor.advanceTime(Duration.standardSeconds(10));
    for (int i = 0; i < 999; i++) {
      testReceiver.replyNextOutstandingMessage();
    }

    // Wait for an ack deadline update
    fakeExecutor.advanceTime(Duration.standardSeconds(60));

    fakeSubscriberServiceImpl.waitForStreamAckDeadline(10);

    subscriber.stopAsync().awaitTerminated();
  }

  @Test
  public void testOpenedChannels() throws Exception {
    if (!isStreamingTest) {
      // This test is not applicable to polling.
      return;
    }

    final int expectedChannelCount =
        Runtime.getRuntime().availableProcessors() * SubscriberImpl.CHANNELS_PER_CORE;

    Subscriber subscriber = startSubscriber(getTestSubscriberBuilder(testReceiver));

    assertEquals(
        expectedChannelCount, fakeSubscriberServiceImpl.waitForOpenedStreams(expectedChannelCount));

    subscriber.stopAsync().awaitTerminated();
  }

  @Test
  public void testFailedChannel_recoverableError_channelReopened() throws Exception {
    if (!isStreamingTest) {
      // This test is not applicable to polling.
      return;
    }

    final int expectedChannelCount =
        Runtime.getRuntime().availableProcessors() * SubscriberImpl.CHANNELS_PER_CORE;

    Subscriber subscriber =
        startSubscriber(
            getTestSubscriberBuilder(testReceiver)
                .setExecutor(Executors.newSingleThreadScheduledExecutor()));

    // Recoverable error
    fakeSubscriberServiceImpl.sendError(new StatusException(Status.INTERNAL));

    assertEquals(1, fakeSubscriberServiceImpl.waitForClosedStreams(1));
    assertEquals(
        expectedChannelCount, fakeSubscriberServiceImpl.waitForOpenedStreams(expectedChannelCount));

    subscriber.stopAsync().awaitTerminated();
  }

  @Test(expected = IllegalStateException.class)
  public void testFailedChannel_fatalError_subscriberFails() throws Exception {
    if (!isStreamingTest) {
      // This test is not applicable to polling.
      throw new IllegalStateException("To fullfil test expectation");
    }

    Subscriber subscriber =
        startSubscriber(
            getTestSubscriberBuilder(testReceiver)
                .setExecutor(Executors.newScheduledThreadPool(10)));

    // Fatal error
    fakeSubscriberServiceImpl.sendError(new StatusException(Status.INVALID_ARGUMENT));

    try {
      subscriber.awaitTerminated();
    } finally {
      // The subscriber must finish with an state error because its FAILED status.
      assertEquals(State.FAILED, subscriber.state());
      assertEquals(
          Status.INVALID_ARGUMENT,
          ((StatusRuntimeException) subscriber.failureCause()).getStatus());
    }
  }

  private Subscriber startSubscriber(Builder testSubscriberBuilder) throws Exception {
    Subscriber subscriber = testSubscriberBuilder.build();
    subscriber.startAsync().awaitRunning();
    if (!isStreamingTest) {
      // Shutdown streaming
      fakeSubscriberServiceImpl.sendError(new StatusException(Status.UNIMPLEMENTED));
    }
    return subscriber;
  }

  private void sendMessages(Iterable<String> ackIds) throws InterruptedException {
    List<ReceivedMessage> messages = new ArrayList<ReceivedMessage>();
    for (String ackId : ackIds) {
      messages.add(ReceivedMessage.newBuilder().setAckId(ackId).setMessage(TEST_MESSAGE).build());
    }
    testReceiver.setExpectedMessages(messages.size());
    if (isStreamingTest) {
      fakeSubscriberServiceImpl.sendStreamingResponse(
          StreamingPullResponse.newBuilder().addAllReceivedMessages(messages).build());
    } else {
      fakeSubscriberServiceImpl.enqueuePullResponse(
          PullResponse.newBuilder().addAllReceivedMessages(messages).build());
    }
    testReceiver.waitForExpectedMessages();
  }

  private Builder getTestSubscriberBuilder(MessageReceiver receiver) {
    return Subscriber.Builder.newBuilder(TEST_SUBSCRIPTION, receiver)
        .setExecutor(fakeExecutor)
        .setCredentials(testCredentials)
        .setChannelBuilder(testChannelBuilder);
  }

  @SuppressWarnings("unchecked")
  private <T> void assertEquivalent(Collection<T> expectedElems, Collection<T> target) {
    List<T> remaining = new ArrayList<T>(target.size());
    remaining.addAll(target);

    for (T expectedElem : expectedElems) {
      if (!remaining.contains(expectedElem)) {
        throw new AssertionError(
            String.format("Expected element %s is not contained in %s", expectedElem, target));
      }
      remaining.remove(expectedElem);
    }
  }

  @SuppressWarnings("unchecked")
  private <T, E> void assertEquivalentWithTransformation(
      Collection<E> expectedElems, Collection<T> target, Function<E, T> transform) {
    List<T> remaining = new ArrayList<T>(target.size());
    remaining.addAll(target);

    for (E expectedElem : expectedElems) {
      if (!remaining.contains(transform.apply(expectedElem))) {
        throw new AssertionError(
            String.format("Expected element %s is not contained in %s", expectedElem, target));
      }
      remaining.remove(expectedElem);
    }
  }
}
