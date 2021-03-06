/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2018-2019 The Feast Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package feast.core.service;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.mockito.MockitoAnnotations.initMocks;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.protobuf.InvalidProtocolBufferException;
import feast.core.config.FeastProperties;
import feast.core.config.FeastProperties.JobProperties;
import feast.core.dao.FeatureSetRepository;
import feast.core.dao.JobRepository;
import feast.core.dao.SourceRepository;
import feast.core.job.JobManager;
import feast.core.job.Runner;
import feast.core.model.*;
import feast.core.util.TestUtil;
import feast.proto.core.CoreServiceProto.ListFeatureSetsRequest.Filter;
import feast.proto.core.CoreServiceProto.ListFeatureSetsResponse;
import feast.proto.core.CoreServiceProto.ListStoresResponse;
import feast.proto.core.FeatureSetProto;
import feast.proto.core.FeatureSetProto.FeatureSetMeta;
import feast.proto.core.FeatureSetProto.FeatureSetSpec;
import feast.proto.core.IngestionJobProto;
import feast.proto.core.SourceProto;
import feast.proto.core.SourceProto.KafkaSourceConfig;
import feast.proto.core.SourceProto.SourceType;
import feast.proto.core.StoreProto;
import feast.proto.core.StoreProto.Store.Subscription;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import lombok.SneakyThrows;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.AsyncResult;

public class JobCoordinatorServiceTest {

  @Rule public final ExpectedException exception = ExpectedException.none();
  @Mock JobRepository jobRepository;
  @Mock JobManager jobManager;
  @Mock SpecService specService;
  @Mock FeatureSetRepository featureSetRepository;
  @Mock private KafkaTemplate<String, FeatureSetSpec> kafkaTemplate;
  @Mock SourceRepository sourceRepository;

  private FeastProperties feastProperties;
  private JobCoordinatorService jcs;

  @Before
  public void setUp() {
    initMocks(this);
    feastProperties = new FeastProperties();
    JobProperties jobProperties = new JobProperties();
    jobProperties.setJobUpdateTimeoutSeconds(5);
    feastProperties.setJobs(jobProperties);

    jcs =
        new JobCoordinatorService(
            jobRepository,
            featureSetRepository,
            sourceRepository,
            specService,
            jobManager,
            feastProperties,
            kafkaTemplate);

    when(kafkaTemplate.sendDefault(any(), any())).thenReturn(new AsyncResult<>(null));
  }

  @Test
  public void shouldDoNothingIfNoStoresFound() throws InvalidProtocolBufferException {
    when(specService.listStores(any())).thenReturn(ListStoresResponse.newBuilder().build());
    jcs.Poll();
    verify(jobRepository, times(0)).saveAndFlush(any());
  }

  @Test
  public void shouldDoNothingIfNoMatchingFeatureSetsFound() throws InvalidProtocolBufferException {
    Store store =
        TestUtil.createStore(
            "test", List.of(Subscription.newBuilder().setName("*").setProject("*").build()));
    StoreProto.Store storeSpec = store.toProto();

    when(specService.listStores(any()))
        .thenReturn(ListStoresResponse.newBuilder().addStore(storeSpec).build());
    when(specService.listFeatureSets(
            Filter.newBuilder().setProject("*").setFeatureSetName("*").build()))
        .thenReturn(ListFeatureSetsResponse.newBuilder().build());
    jcs.Poll();
    verify(jobRepository, times(0)).saveAndFlush(any());
  }

  @Test
  public void shouldGenerateAndSubmitJobsIfAny() throws InvalidProtocolBufferException {
    Store store =
        TestUtil.createStore(
            "test", List.of(Subscription.newBuilder().setName("*").setProject("project1").build()));
    StoreProto.Store storeSpec = store.toProto();
    SourceProto.Source sourceSpec =
        SourceProto.Source.newBuilder()
            .setType(SourceType.KAFKA)
            .setKafkaSourceConfig(
                KafkaSourceConfig.newBuilder()
                    .setTopic("topic")
                    .setBootstrapServers("servers:9092")
                    .build())
            .build();
    Source source = Source.fromProto(sourceSpec);

    FeatureSetProto.FeatureSet featureSetSpec1 =
        FeatureSetProto.FeatureSet.newBuilder()
            .setSpec(
                FeatureSetSpec.newBuilder()
                    .setSource(sourceSpec)
                    .setProject("project1")
                    .setName("features1"))
            .setMeta(FeatureSetMeta.newBuilder())
            .build();
    FeatureSet featureSet1 = FeatureSet.fromProto(featureSetSpec1);
    FeatureSetProto.FeatureSet featureSetSpec2 =
        FeatureSetProto.FeatureSet.newBuilder()
            .setSpec(
                FeatureSetSpec.newBuilder()
                    .setSource(sourceSpec)
                    .setProject("project1")
                    .setName("features2"))
            .setMeta(FeatureSetMeta.newBuilder())
            .build();
    FeatureSet featureSet2 = FeatureSet.fromProto(featureSetSpec2);
    ArgumentCaptor<List<Job>> jobArgCaptor = ArgumentCaptor.forClass(List.class);

    Job expectedInput =
        Job.builder()
            .setId("id")
            .setExtId("")
            .setRunner(Runner.DATAFLOW)
            .setSource(source)
            .setStore(store)
            .setFeatureSetJobStatuses(TestUtil.makeFeatureSetJobStatus(featureSet1, featureSet2))
            .setStatus(JobStatus.PENDING)
            .build();

    Job expected =
        expectedInput.toBuilder().setExtId("extid1").setStatus(JobStatus.RUNNING).build();

    when(featureSetRepository.findAllByNameLikeAndProject_NameLikeOrderByNameAsc("%", "project1"))
        .thenReturn(Lists.newArrayList(featureSet1, featureSet2));
    when(sourceRepository.findFirstByTypeAndConfigOrderByIdAsc(
            source.getType(), source.getConfig()))
        .thenReturn(source);
    when(specService.listStores(any()))
        .thenReturn(ListStoresResponse.newBuilder().addStore(storeSpec).build());

    when(jobManager.startJob(expectedInput)).thenReturn(expected);
    when(jobManager.getRunnerType()).thenReturn(Runner.DATAFLOW);

    when(jobRepository.findByStatus(JobStatus.RUNNING)).thenReturn(List.of());
    when(jobRepository
            .findFirstBySourceTypeAndSourceConfigAndStoreNameAndStatusNotInOrderByLastUpdatedDesc(
                source.getType(),
                source.getConfig(),
                store.getName(),
                JobStatus.getTerminalStates()))
        .thenReturn(Optional.empty());

    jcs.Poll();
    verify(jobRepository, times(1)).saveAll(jobArgCaptor.capture());
    List<Job> actual = jobArgCaptor.getValue();
    assertThat(actual, containsInAnyOrder(expected));
  }

  @Test
  public void shouldGroupJobsBySource() throws InvalidProtocolBufferException {
    Store store =
        TestUtil.createStore(
            "test", List.of(Subscription.newBuilder().setName("*").setProject("project1").build()));
    StoreProto.Store storeSpec = store.toProto();

    Source source1 = TestUtil.createKafkaSource("servers:9092", "topic", false);
    Source source2 = TestUtil.createKafkaSource("others.servers:9092", "topic", false);

    FeatureSet featureSet1 = TestUtil.createEmptyFeatureSet("features1", source1);
    FeatureSet featureSet2 = TestUtil.createEmptyFeatureSet("features2", source2);

    Job expectedInput1 =
        Job.builder()
            .setId("id1")
            .setExtId("")
            .setRunner(Runner.DATAFLOW)
            .setSource(source1)
            .setStore(store)
            .setFeatureSetJobStatuses(TestUtil.makeFeatureSetJobStatus(featureSet1))
            .setStatus(JobStatus.PENDING)
            .build();

    Job expected1 =
        expectedInput1.toBuilder().setExtId("extid1").setStatus(JobStatus.RUNNING).build();

    Job expectedInput2 =
        Job.builder()
            .setId("id2")
            .setExtId("")
            .setRunner(Runner.DATAFLOW)
            .setSource(source2)
            .setStore(store)
            .setFeatureSetJobStatuses(TestUtil.makeFeatureSetJobStatus(featureSet2))
            .setStatus(JobStatus.PENDING)
            .build();

    Job expected2 =
        expectedInput2.toBuilder().setExtId("extid2").setStatus(JobStatus.RUNNING).build();

    when(featureSetRepository.findAllByNameLikeAndProject_NameLikeOrderByNameAsc("%", "project1"))
        .thenReturn(Lists.newArrayList(featureSet1, featureSet2));
    when(sourceRepository.findFirstByTypeAndConfigOrderByIdAsc(
            source1.getType(), source1.getConfig()))
        .thenReturn(source1);
    when(sourceRepository.findFirstByTypeAndConfigOrderByIdAsc(
            source2.getType(), source2.getConfig()))
        .thenReturn(source2);
    when(specService.listStores(any()))
        .thenReturn(ListStoresResponse.newBuilder().addStore(storeSpec).build());

    when(jobManager.startJob(expectedInput1)).thenReturn(expected1);
    when(jobManager.startJob(expectedInput2)).thenReturn(expected2);
    when(jobManager.getRunnerType()).thenReturn(Runner.DATAFLOW);

    when(jobRepository.findByStatus(JobStatus.RUNNING)).thenReturn(List.of());
    when(jobRepository
            .findFirstBySourceTypeAndSourceConfigAndStoreNameAndStatusNotInOrderByLastUpdatedDesc(
                source1.getType(),
                source1.getConfig(),
                storeSpec.getName(),
                JobStatus.getTerminalStates()))
        .thenReturn(Optional.empty());
    when(jobRepository
            .findFirstBySourceTypeAndSourceConfigAndStoreNameAndStatusNotInOrderByLastUpdatedDesc(
                source2.getType(),
                source2.getConfig(),
                storeSpec.getName(),
                JobStatus.getTerminalStates()))
        .thenReturn(Optional.empty());

    jcs.Poll();

    ArgumentCaptor<List<Job>> jobArgCaptor = ArgumentCaptor.forClass(List.class);
    verify(jobRepository, times(1)).saveAll(jobArgCaptor.capture());
    List<Job> actual = jobArgCaptor.getValue();

    for (Job expectedJob : List.of(expected1, expected2)) {
      assertTrue(actual.contains(expectedJob));
    }
  }

  @Test
  public void shouldGroupJobsBySourceAndIgnoreDuplicateSourceObjects()
      throws InvalidProtocolBufferException {
    Store store =
        TestUtil.createStore(
            "test", List.of(Subscription.newBuilder().setName("*").setProject("project1").build()));
    StoreProto.Store storeSpec = store.toProto();

    // simulate duplicate source objects: create source objects from the same spec but with
    // different ids
    Source source1 = TestUtil.createKafkaSource("servers:9092", "topic", false);
    source1.setId(1);
    Source source2 = TestUtil.createKafkaSource("servers:9092", "topic", false);
    source2.setId(2);

    FeatureSet featureSet1 = TestUtil.createEmptyFeatureSet("features1", source1);
    FeatureSet featureSet2 = TestUtil.createEmptyFeatureSet("features2", source2);

    Job expectedInput =
        Job.builder()
            .setId("id")
            .setExtId("")
            .setSource(source1)
            .setStore(store)
            .setRunner(Runner.DATAFLOW)
            .setFeatureSetJobStatuses(TestUtil.makeFeatureSetJobStatus(featureSet1, featureSet2))
            .setStatus(JobStatus.PENDING)
            .build();

    Job expected =
        expectedInput.toBuilder().setExtId("extid1").setStatus(JobStatus.RUNNING).build();

    when(featureSetRepository.findAllByNameLikeAndProject_NameLikeOrderByNameAsc("%", "project1"))
        .thenReturn(Lists.newArrayList(featureSet1, featureSet2));
    when(sourceRepository.findFirstByTypeAndConfigOrderByIdAsc(
            source1.getType(), source1.getConfig()))
        .thenReturn(source1);
    when(sourceRepository.findFirstByTypeAndConfigOrderByIdAsc(
            source2.getType(), source2.getConfig()))
        .thenReturn(source1);
    when(specService.listStores(any()))
        .thenReturn(ListStoresResponse.newBuilder().addStore(storeSpec).build());

    when(jobManager.startJob(expectedInput)).thenReturn(expected);
    when(jobManager.getRunnerType()).thenReturn(Runner.DATAFLOW);

    when(jobRepository.findByStatus(JobStatus.RUNNING)).thenReturn(List.of());
    when(jobRepository
            .findFirstBySourceTypeAndSourceConfigAndStoreNameAndStatusNotInOrderByLastUpdatedDesc(
                source1.getType(),
                source1.getConfig(),
                storeSpec.getName(),
                JobStatus.getTerminalStates()))
        .thenReturn(Optional.empty());

    ArgumentCaptor<List<Job>> jobArgCaptor = ArgumentCaptor.forClass(List.class);

    jcs.Poll();
    verify(jobRepository, times(1)).saveAll(jobArgCaptor.capture());
    List<Job> actual = jobArgCaptor.getValue();
    assertThat(actual, containsInAnyOrder(expected));
  }

  @Test
  public void shouldStopDuplicateJobsForSource() throws InvalidProtocolBufferException {
    Store store =
        TestUtil.createStore(
            "test", List.of(Subscription.newBuilder().setName("*").setProject("project1").build()));
    StoreProto.Store storeSpec = store.toProto();

    Source source = TestUtil.createKafkaSource("servers:9092", "topic", false);

    FeatureSet featureSet = TestUtil.createEmptyFeatureSet("features2", source);

    // simulate 3 running jobs, all serving the same source to store pairing.
    // JobCoordinatorService should abort 2 the extra jobs.
    List<Job> inputJobs = new ArrayList<>();
    List<Job> expectedJobs = new ArrayList<>();
    List<Job> extraJobs = new ArrayList();
    for (int i = 0; i < 3; i++) {
      inputJobs.add(
          Job.builder()
              .setId(String.format("id%d", i))
              .setExtId(String.format("extid%d", i))
              .setSource(source)
              .setStore(store)
              .setRunner(Runner.DATAFLOW)
              .setFeatureSetJobStatuses(TestUtil.makeFeatureSetJobStatus(featureSet))
              .setStatus(JobStatus.RUNNING)
              .build());

      JobStatus targetStatus = (i >= 1) ? JobStatus.ABORTED : JobStatus.RUNNING;
      expectedJobs.add(inputJobs.get(i).toBuilder().setStatus(targetStatus).build());

      if (targetStatus == JobStatus.ABORTED) {
        when(jobManager.abortJob(inputJobs.get(i))).thenReturn(expectedJobs.get(i));
        extraJobs.add(inputJobs.get(i));
      }
    }

    when(featureSetRepository.findAllByNameLikeAndProject_NameLikeOrderByNameAsc("%", "project1"))
        .thenReturn(Lists.newArrayList(featureSet));
    when(sourceRepository.findFirstByTypeAndConfigOrderByIdAsc(
            source.getType(), source.getConfig()))
        .thenReturn(source);
    when(specService.listStores(any()))
        .thenReturn(ListStoresResponse.newBuilder().addStore(storeSpec).build());

    when(jobManager.getJobStatus(inputJobs.get(0))).thenReturn(JobStatus.RUNNING);
    when(jobManager.getRunnerType()).thenReturn(Runner.DATAFLOW);

    when(jobRepository
            .findFirstBySourceTypeAndSourceConfigAndStoreNameAndStatusNotInOrderByLastUpdatedDesc(
                source.getType(),
                source.getConfig(),
                store.getName(),
                JobStatus.getTerminalStates()))
        .thenReturn(Optional.of(inputJobs.get(0)));
    when(jobRepository.findByStatus(JobStatus.RUNNING)).thenReturn(inputJobs);

    jcs.Poll();

    ArgumentCaptor<Job> jobCaptor = ArgumentCaptor.forClass(Job.class);

    verify(jobManager, times(2)).abortJob(jobCaptor.capture());
    List<Job> abortedJobs = jobCaptor.getAllValues();
    for (Job extraJob : extraJobs) {
      assertTrue(abortedJobs.contains(extraJob));
    }

    ArgumentCaptor<List<Job>> jobListCaptor = ArgumentCaptor.forClass(List.class);
    verify(jobRepository, times(1)).saveAll(jobListCaptor.capture());
    List<Job> actual = jobListCaptor.getValue();
    for (Job expectedJob : expectedJobs) {
      assertTrue(actual.contains(expectedJob));
    }
  }

  @Test
  public void shouldUseStoreSubscriptionToMapStore() throws InvalidProtocolBufferException {
    Store store1 =
        TestUtil.createStore(
            "test",
            List.of(Subscription.newBuilder().setName("features1").setProject("*").build()));
    StoreProto.Store store1Spec = store1.toProto();

    Store store2 =
        TestUtil.createStore(
            "test",
            List.of(Subscription.newBuilder().setName("features2").setProject("*").build()));
    StoreProto.Store store2Spec = store2.toProto();

    Source source1 = TestUtil.createKafkaSource("servers:9092", "topic", false);
    Source source2 = TestUtil.createKafkaSource("other.servers:9092", "topic", false);

    FeatureSet featureSet1 = TestUtil.createEmptyFeatureSet("feature1", source1);
    FeatureSet featureSet2 = TestUtil.createEmptyFeatureSet("feature2", source2);

    Job expectedInput1 =
        Job.builder()
            .setId("id1")
            .setExtId("")
            .setRunner(Runner.DATAFLOW)
            .setSource(source1)
            .setStore(store1)
            .setFeatureSetJobStatuses(TestUtil.makeFeatureSetJobStatus(featureSet1))
            .setStatus(JobStatus.PENDING)
            .build();

    Job expected1 =
        expectedInput1.toBuilder().setExtId("extid1").setStatus(JobStatus.RUNNING).build();

    Job expectedInput2 =
        Job.builder()
            .setId("id2")
            .setExtId("")
            .setRunner(Runner.DATAFLOW)
            .setSource(source2)
            .setStore(store2)
            .setFeatureSetJobStatuses(TestUtil.makeFeatureSetJobStatus(featureSet2))
            .setStatus(JobStatus.PENDING)
            .build();

    Job expected2 =
        expectedInput2.toBuilder().setExtId("extid2").setStatus(JobStatus.RUNNING).build();

    ArgumentCaptor<List<Job>> jobArgCaptor = ArgumentCaptor.forClass(List.class);

    when(featureSetRepository.findAllByNameLikeAndProject_NameLikeOrderByNameAsc("features1", "%"))
        .thenReturn(Lists.newArrayList(featureSet1));
    when(featureSetRepository.findAllByNameLikeAndProject_NameLikeOrderByNameAsc("features2", "%"))
        .thenReturn(Lists.newArrayList(featureSet2));
    when(sourceRepository.findFirstByTypeAndConfigOrderByIdAsc(
            source1.getType(), source1.getConfig()))
        .thenReturn(source1);
    when(sourceRepository.findFirstByTypeAndConfigOrderByIdAsc(
            source2.getType(), source2.getConfig()))
        .thenReturn(source2);
    when(specService.listStores(any()))
        .thenReturn(
            ListStoresResponse.newBuilder().addStore(store1Spec).addStore(store2Spec).build());

    when(jobManager.startJob(expectedInput1)).thenReturn(expected1);
    when(jobManager.startJob(expectedInput2)).thenReturn(expected2);
    when(jobManager.getRunnerType()).thenReturn(Runner.DATAFLOW);

    when(jobRepository.findByStatus(JobStatus.RUNNING)).thenReturn(List.of());
    when(jobRepository
            .findFirstBySourceTypeAndSourceConfigAndStoreNameAndStatusNotInOrderByLastUpdatedDesc(
                source1.getType(),
                source1.getConfig(),
                store1.getName(),
                JobStatus.getTerminalStates()))
        .thenReturn(Optional.empty());
    when(jobRepository
            .findFirstBySourceTypeAndSourceConfigAndStoreNameAndStatusNotInOrderByLastUpdatedDesc(
                source2.getType(),
                source1.getConfig(),
                store2.getName(),
                JobStatus.getTerminalStates()))
        .thenReturn(Optional.empty());

    jcs.Poll();

    verify(jobRepository, times(1)).saveAll(jobArgCaptor.capture());
    List<Job> actual = jobArgCaptor.getValue();

    for (Job expectedJob : List.of(expected1, expected2)) {
      assertTrue(actual.contains(expectedJob));
    }
  }

  @Test
  public void shouldSendPendingFeatureSetToJobs() {
    FeatureSet fs1 =
        TestUtil.CreateFeatureSet(
            "fs_1", "project", Collections.emptyList(), Collections.emptyList());
    fs1.setVersion(2);

    FeatureSetJobStatus status1 =
        TestUtil.CreateFeatureSetJobStatusWithJob(
            JobStatus.RUNNING, FeatureSetProto.FeatureSetJobDeliveryStatus.STATUS_DELIVERED, 1);
    FeatureSetJobStatus status2 =
        TestUtil.CreateFeatureSetJobStatusWithJob(
            JobStatus.RUNNING, FeatureSetProto.FeatureSetJobDeliveryStatus.STATUS_DELIVERED, 1);
    FeatureSetJobStatus status3 =
        TestUtil.CreateFeatureSetJobStatusWithJob(
            JobStatus.ABORTED, FeatureSetProto.FeatureSetJobDeliveryStatus.STATUS_DELIVERED, 2);

    // spec needs to be send
    fs1.getJobStatuses().addAll(ImmutableList.of(status1, status2, status3));

    FeatureSet fs2 =
        TestUtil.CreateFeatureSet(
            "fs_2", "project", Collections.emptyList(), Collections.emptyList());
    fs2.setVersion(5);

    // spec already sent to kafka
    fs2.getJobStatuses()
        .addAll(
            ImmutableList.of(
                TestUtil.CreateFeatureSetJobStatusWithJob(
                    JobStatus.RUNNING,
                    FeatureSetProto.FeatureSetJobDeliveryStatus.STATUS_IN_PROGRESS,
                    5)));

    // feature set without running jobs attached
    FeatureSet fs3 =
        TestUtil.CreateFeatureSet(
            "fs_3", "project", Collections.emptyList(), Collections.emptyList());
    fs3.getJobStatuses()
        .addAll(
            ImmutableList.of(
                TestUtil.CreateFeatureSetJobStatusWithJob(
                    JobStatus.ABORTED,
                    FeatureSetProto.FeatureSetJobDeliveryStatus.STATUS_IN_PROGRESS,
                    5)));

    when(featureSetRepository.findAllByStatus(FeatureSetProto.FeatureSetStatus.STATUS_PENDING))
        .thenReturn(ImmutableList.of(fs1, fs2, fs3));

    jcs.notifyJobsWhenFeatureSetUpdated();

    verify(kafkaTemplate).sendDefault(eq(fs1.getReference()), any(FeatureSetSpec.class));
    verify(kafkaTemplate, never()).sendDefault(eq(fs2.getReference()), any(FeatureSetSpec.class));
    verify(kafkaTemplate, never()).sendDefault(eq(fs3.getReference()), any(FeatureSetSpec.class));

    assertThat(status1.getVersion(), is(2));
    assertThat(
        status1.getDeliveryStatus(),
        is(FeatureSetProto.FeatureSetJobDeliveryStatus.STATUS_IN_PROGRESS));

    assertThat(status2.getVersion(), is(2));
    assertThat(
        status2.getDeliveryStatus(),
        is(FeatureSetProto.FeatureSetJobDeliveryStatus.STATUS_IN_PROGRESS));

    assertThat(status3.getVersion(), is(2));
    assertThat(
        status3.getDeliveryStatus(),
        is(FeatureSetProto.FeatureSetJobDeliveryStatus.STATUS_DELIVERED));
  }

  @Test
  @SneakyThrows
  public void shouldNotUpdateJobStatusVersionWhenKafkaUnavailable() {
    FeatureSet fsInTest =
        TestUtil.CreateFeatureSet(
            "fs_1", "project", Collections.emptyList(), Collections.emptyList());
    fsInTest.setVersion(2);

    FeatureSetJobStatus featureSetJobStatus =
        TestUtil.CreateFeatureSetJobStatusWithJob(
            JobStatus.RUNNING, FeatureSetProto.FeatureSetJobDeliveryStatus.STATUS_DELIVERED, 1);
    fsInTest.getJobStatuses().add(featureSetJobStatus);

    CancellationException exc = new CancellationException();
    when(kafkaTemplate.sendDefault(eq(fsInTest.getReference()), any()).get()).thenThrow(exc);
    when(featureSetRepository.findAllByStatus(FeatureSetProto.FeatureSetStatus.STATUS_PENDING))
        .thenReturn(ImmutableList.of(fsInTest));

    jcs.notifyJobsWhenFeatureSetUpdated();
    assertThat(featureSetJobStatus.getVersion(), is(1));
  }

  @Test
  public void specAckListenerShouldDoNothingWhenMessageIsOutdated() {
    FeatureSet fsInTest =
        TestUtil.CreateFeatureSet(
            "fs", "project", Collections.emptyList(), Collections.emptyList());
    FeatureSetJobStatus j1 =
        TestUtil.CreateFeatureSetJobStatusWithJob(
            JobStatus.RUNNING, FeatureSetProto.FeatureSetJobDeliveryStatus.STATUS_IN_PROGRESS, 1);
    FeatureSetJobStatus j2 =
        TestUtil.CreateFeatureSetJobStatusWithJob(
            JobStatus.RUNNING, FeatureSetProto.FeatureSetJobDeliveryStatus.STATUS_IN_PROGRESS, 1);

    fsInTest.getJobStatuses().addAll(Arrays.asList(j1, j2));

    when(featureSetRepository.findFeatureSetByNameAndProject_Name(
            fsInTest.getName(), fsInTest.getProject().getName()))
        .thenReturn(fsInTest);

    jcs.listenAckFromJobs(newAckMessage("project/invalid", 0, j1.getJob().getId()));
    jcs.listenAckFromJobs(newAckMessage(fsInTest.getReference(), 0, ""));
    jcs.listenAckFromJobs(newAckMessage(fsInTest.getReference(), -1, j1.getJob().getId()));

    assertThat(
        j1.getDeliveryStatus(), is(FeatureSetProto.FeatureSetJobDeliveryStatus.STATUS_IN_PROGRESS));
    assertThat(
        j2.getDeliveryStatus(), is(FeatureSetProto.FeatureSetJobDeliveryStatus.STATUS_IN_PROGRESS));
  }

  @Test
  public void specAckListenerShouldUpdateFeatureSetStatus() {
    FeatureSet fsInTest =
        TestUtil.CreateFeatureSet(
            "fs", "project", Collections.emptyList(), Collections.emptyList());
    fsInTest.setStatus(FeatureSetProto.FeatureSetStatus.STATUS_PENDING);

    FeatureSetJobStatus j1 =
        TestUtil.CreateFeatureSetJobStatusWithJob(
            JobStatus.RUNNING, FeatureSetProto.FeatureSetJobDeliveryStatus.STATUS_IN_PROGRESS, 1);
    FeatureSetJobStatus j2 =
        TestUtil.CreateFeatureSetJobStatusWithJob(
            JobStatus.RUNNING, FeatureSetProto.FeatureSetJobDeliveryStatus.STATUS_IN_PROGRESS, 1);
    FeatureSetJobStatus j3 =
        TestUtil.CreateFeatureSetJobStatusWithJob(
            JobStatus.ABORTED, FeatureSetProto.FeatureSetJobDeliveryStatus.STATUS_IN_PROGRESS, 1);

    fsInTest.getJobStatuses().addAll(Arrays.asList(j1, j2, j3));

    when(featureSetRepository.findFeatureSetByNameAndProject_Name(
            fsInTest.getName(), fsInTest.getProject().getName()))
        .thenReturn(fsInTest);

    jcs.listenAckFromJobs(
        newAckMessage(fsInTest.getReference(), fsInTest.getVersion(), j1.getJob().getId()));

    assertThat(
        j1.getDeliveryStatus(), is(FeatureSetProto.FeatureSetJobDeliveryStatus.STATUS_DELIVERED));
    assertThat(fsInTest.getStatus(), is(FeatureSetProto.FeatureSetStatus.STATUS_PENDING));

    jcs.listenAckFromJobs(
        newAckMessage(fsInTest.getReference(), fsInTest.getVersion(), j2.getJob().getId()));

    assertThat(
        j2.getDeliveryStatus(), is(FeatureSetProto.FeatureSetJobDeliveryStatus.STATUS_DELIVERED));

    assertThat(fsInTest.getStatus(), is(FeatureSetProto.FeatureSetStatus.STATUS_READY));
  }

  private ConsumerRecord<String, IngestionJobProto.FeatureSetSpecAck> newAckMessage(
      String key, int version, String jobName) {
    return new ConsumerRecord<>(
        "topic",
        0,
        0,
        key,
        IngestionJobProto.FeatureSetSpecAck.newBuilder()
            .setFeatureSetVersion(version)
            .setJobName(jobName)
            .build());
  }
}
