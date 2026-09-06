package de.zorro909.skywright.backend.runstore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class RunStoreMeasurementsTest {

	@Test
	void overflowKeepsExactMissingIdentitiesAndOutOfOrderTimeBounds() {
		var records = new RunStoreMeasurements("run", "storage", 2);
		records.record("GetObject", 3, "read", Instant.ofEpochSecond(3), false);
		records.record("GetObject", 2, "read", Instant.ofEpochSecond(1), true);
		records.record("GetObject", 4, "read", Instant.ofEpochSecond(2), true);
		records.record("ListObjectsV2", 0, "control", Instant.ofEpochSecond(4), true);

		var batch = records.drain();
		assertThat(batch.runId()).isEqualTo("run");
		assertThat(batch.provenance()).isEqualTo("storage");
		assertThat(batch.throughSequence()).isEqualTo(4);
		assertThat(batch.gap())
			.isEqualTo(new RunStoreMeasurementBatch.Gap(1, 2, Instant.ofEpochSecond(1), Instant.ofEpochSecond(3)));
		assertThat(batch.gap().count()).isEqualTo(2);
		assertThat(batch.measurements()).extracting(RunStoreOperationMeasurement::requestNumber)
			.containsExactly(3L, 4L);
		assertThat(batch.measurements())
			.allSatisfy(item -> assertThat(item.producerId()).isEqualTo(batch.producerId()));
		assertThat(records.snapshot()).isEmpty();

		records.record("GetObject", 8, "read", Instant.ofEpochSecond(5), true);
		var next = records.drain();
		assertThat(next.producerId()).isEqualTo(batch.producerId());
		assertThat(next.throughSequence()).isEqualTo(5);
		assertThat(next.gap()).isNull();
		assertThat(next.measurements()).extracting(RunStoreOperationMeasurement::requestNumber).containsExactly(5L);
		assertThat(records.drain().measurements()).isEmpty();
		assertThat(batch.measurements()).hasSize(2);
		assertThat(new RunStoreMeasurements("run", "storage", 2).drain().producerId()).isNotEqualTo(batch.producerId());
	}

	@Test
	void concurrentProducersStayBoundedWithoutLosingSequenceAccounting() throws Exception {
		var records = new RunStoreMeasurements("run", "storage", 256);
		try (var workers = Executors.newFixedThreadPool(4)) {
			var tasks = new ArrayList<Future<?>>();
			for (int worker = 0; worker < 4; worker++) {
				tasks.add(workers.submit(() -> {
					for (int request = 0; request < 10000; request++) {
						records.record("GetObject", 1, "read", Instant.EPOCH, true);
					}
				}));
			}
			for (var task : tasks) {
				task.get(10, TimeUnit.SECONDS);
			}
		}
		var batch = records.drain();
		assertThat(batch.measurements()).hasSize(256);
		assertThat(batch.throughSequence()).isEqualTo(40000);
		assertThat(batch.gap().count() + batch.measurements().size()).isEqualTo(40000);
		assertThat(batch.measurements()).extracting(RunStoreOperationMeasurement::requestNumber)
			.doesNotHaveDuplicates()
			.isSorted();
	}

	@Test
	void invalidCapacityIsRejectedBeforeRecording() {
		assertThatThrownBy(() -> new RunStoreMeasurements("run", "storage", 0))
			.isInstanceOf(IllegalArgumentException.class);
	}

}
