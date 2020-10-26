/*
 * Copyright 2020 Red Hat Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.vertx.kafka.client.common.tracing;

import io.vertx.core.Context;
import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import io.vertx.core.spi.tracing.TagExtractor;
import io.vertx.core.spi.tracing.VertxTracer;
import io.vertx.core.tracing.TracingOptions;
import io.vertx.core.tracing.TracingPolicy;
import io.vertx.ext.unit.Async;
import io.vertx.ext.unit.TestContext;
import io.vertx.kafka.client.common.KafkaClientOptions;
import io.vertx.kafka.client.consumer.KafkaReadStream;
import io.vertx.kafka.client.producer.KafkaProducer;
import io.vertx.kafka.client.producer.KafkaProducerRecord;
import io.vertx.kafka.client.producer.KafkaWriteStream;
import io.vertx.kafka.client.producer.impl.KafkaProducerImpl;
import io.vertx.kafka.client.tests.KafkaClusterTestBase;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.OffsetResetStrategy;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.Collections;
import java.util.Date;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.BiConsumer;

/**
 * Tracing tests
 */
public class TracingTest extends KafkaClusterTestBase {

  private Vertx vertx;
  private KafkaWriteStream<String, String> producer;
  private KafkaReadStream<String, String> consumer;
  private TestTracer tracer;

  @Before
  public void beforeTest(TestContext ctx) {
    tracer = new TestTracer(ctx);
    vertx = Vertx.vertx(new VertxOptions().setTracingOptions(new TracingOptions().setFactory(tracingOptions -> tracer)));
  }

  @After
  public void afterTest(TestContext ctx) {
    close(ctx, producer);
    close(ctx, consumer);
    vertx.close(ctx.asyncAssertSuccess());
  }

  @Test
  public void testTracing(TestContext ctx) {
    String topicName = "TestTracing";
    Properties pConf = kafkaCluster.useTo().getProducerProperties("testTracing_producer");
    pConf.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
    pConf.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
    producer = producer(vertx, pConf);
    producer.exceptionHandler(ctx::fail);
    KafkaProducer<String, String> producer = new KafkaProducerImpl<>(this.vertx, this.producer);

    Properties cConf = kafkaCluster.useTo().getConsumerProperties("testTracing_consumer", "testTracing_consumer", OffsetResetStrategy.EARLIEST);
    cConf.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    cConf.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    consumer = KafkaReadStream.create(vertx, cConf);
    consumer.exceptionHandler(ctx::fail);
    consumer.handler(rec -> {});
    consumer.subscribe(Collections.singleton(topicName));

    int numMessages = 1000;
    tracer.init(topicName, numMessages, numMessages);
    for (int i = 0; i < numMessages; i++) {
      producer.write(KafkaProducerRecord.create(topicName, "key-" + i, "value-" + i, 0));
    }
    tracer.assertAllDone(0);
  }

  @Test
  public void testTracingFailure(TestContext ctx) {
    Properties pConf = kafkaCluster.useTo().getProducerProperties("testTracing_producer");
    pConf.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
    pConf.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);

    tracer.init("topic", 2, 0);
    Date invalidValue = new Date();
    KafkaProducer p = KafkaProducer.create(vertx, pConf);
    p.write(KafkaProducerRecord.create("topic", "key", "valid string"));
    p.write(KafkaProducerRecord.create("topic", "key", invalidValue));
    tracer.assertAllDone(1);
  }

  @Test
  public void testTracingIgnoreConsumer(TestContext ctx) {
    String topicName = "TestTracingIgnoreC";
    KafkaClientOptions options = new KafkaClientOptions()
      .setConfig(mapConfig(kafkaCluster.useTo().getProducerProperties("testTracing_producer")))
      .setConfig(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class)
      .setConfig(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class)
      .setTracingPolicy(TracingPolicy.ALWAYS);

    producer = producer(vertx, options);
    producer.exceptionHandler(ctx::fail);
    KafkaProducer<String, String> producer = new KafkaProducerImpl<>(this.vertx, this.producer);

    options = new KafkaClientOptions()
      .setConfig(mapConfig(kafkaCluster.useTo().getConsumerProperties("testTracing_consumer", "testTracing_consumer", OffsetResetStrategy.EARLIEST)))
      .setConfig(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class)
      .setConfig(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class)
      .setTracingPolicy(TracingPolicy.IGNORE);
    consumer = KafkaReadStream.create(vertx, options);
    consumer.exceptionHandler(ctx::fail);

    int numMessages = 10;
    Async done = ctx.async(numMessages);
    consumer.handler(rec -> done.countDown());
    consumer.subscribe(Collections.singleton(topicName));

    tracer.init(topicName, numMessages, 0);
    for (int i = 0; i < numMessages; i++) {
      producer.write(KafkaProducerRecord.create(topicName, "key-" + i, "value-" + i, 0));
    }
    tracer.assertAllDone(0);
    done.awaitSuccess(10000);
  }

  @Test
  public void testTracingIgnoreProducer(TestContext ctx) {
    String topicName = "TestTracingIgnoreP";
    KafkaClientOptions options = new KafkaClientOptions()
      .setConfig(mapConfig(kafkaCluster.useTo().getProducerProperties("testTracing_producer")))
      .setConfig(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class)
      .setConfig(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class)
      .setTracingPolicy(TracingPolicy.IGNORE);

    producer = producer(vertx, options);
    producer.exceptionHandler(ctx::fail);
    KafkaProducer<String, String> producer = new KafkaProducerImpl<>(this.vertx, this.producer);

    options = new KafkaClientOptions()
      .setConfig(mapConfig(kafkaCluster.useTo().getConsumerProperties("testTracing_consumer", "testTracing_consumer", OffsetResetStrategy.EARLIEST)))
      .setConfig(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class)
      .setConfig(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class)
      .setTracingPolicy(TracingPolicy.ALWAYS);
    consumer = KafkaReadStream.create(vertx, options);
    consumer.exceptionHandler(ctx::fail);

    int numMessages = 10;
    Async done = ctx.async(numMessages);
    consumer.handler(rec -> done.countDown());
    consumer.subscribe(Collections.singleton(topicName));

    tracer.init(topicName, 0, numMessages);
    for (int i = 0; i < numMessages; i++) {
      producer.write(KafkaProducerRecord.create(topicName, "key-" + i, "value-" + i, 0));
    }
    tracer.assertAllDone(0);
    done.awaitSuccess(10000);
  }

  private static class TestTracer implements VertxTracer<String, String> {
    private final TestContext ctx;
    private String topic;
    private Async done;
    private LongAdder failuresCount;
    private LongAdder sentCount;
    private LongAdder receivedCount;

    private TestTracer(TestContext ctx) {
      this.ctx = ctx;
    }

    @Override
    public <R> String receiveRequest(Context context, TracingPolicy policy, R request, String operation, Iterable<Map.Entry<String, String>> headers, TagExtractor<R> tagExtractor) {
      receivedCount.decrement();
      if (receivedCount.intValue() < 0) {
        ctx.fail("Unexpected call to receiveRequest");
      }
      Map<String, String> tags = tagExtractor.extract(request);
      ctx.assertEquals("kafka_receive", operation);
      ctx.assertEquals("consumer", tags.get("span.kind"));
      ctx.assertEquals("localhost:9092", tags.get("peer.address"));
      ctx.assertEquals("localhost", tags.get("peer.hostname"));
      ctx.assertEquals("9092", tags.get("peer.port"));
      ctx.assertEquals(topic, tags.get("message_bus.destination"));
      ctx.assertEquals("kafka", tags.get("peer.service"));
      done.countDown();
      return "SPAN-CONSUMER";
    }

    @Override
    public <R> void sendResponse(Context context, R response, String payload, Throwable failure, TagExtractor<R> tagExtractor) {
      ctx.assertEquals("SPAN-CONSUMER", payload);
      done.countDown();
    }

    @Override
    public <R> String sendRequest(Context context, TracingPolicy policy, R request, String operation, BiConsumer<String, String> headers, TagExtractor<R> tagExtractor) {
      sentCount.decrement();
      if (sentCount.intValue() < 0) {
        ctx.fail("Unexpected call to sendRequest");
      }
      Map<String, String> tags = tagExtractor.extract(request);
      ctx.assertEquals("kafka_send", operation);
      ctx.assertEquals("producer", tags.get("span.kind"));
      ctx.assertEquals("localhost:9092", tags.get("peer.address"));
      ctx.assertEquals("localhost", tags.get("peer.hostname"));
      ctx.assertEquals("9092", tags.get("peer.port"));
      ctx.assertEquals(topic, tags.get("message_bus.destination"));
      ctx.assertEquals("kafka", tags.get("peer.service"));
      done.countDown();
      return "SPAN-PRODUCER";
    }

    @Override
    public <R> void receiveResponse(Context context, R response, String payload, Throwable failure, TagExtractor<R> tagExtractor) {
      ctx.assertEquals("SPAN-PRODUCER", payload);
      if (failure != null) {
        failuresCount.increment();
      }
      done.countDown();
    }

    void init(String topic, int sent, int received) {
      sentCount = new LongAdder();
      sentCount.add(sent);
      receivedCount = new LongAdder();
      receivedCount.add(received);
      done = ctx.async(2 * sent + 2 * received);
      this.topic = topic;
      failuresCount = new LongAdder();
    }

    void assertAllDone(int expectedFailures) {
      done.await(2000);
      ctx.assertEquals(expectedFailures, failuresCount.intValue());
      ctx.assertEquals(0, sentCount.intValue());
      ctx.assertEquals(0, receivedCount.intValue());
    }
  }
}
