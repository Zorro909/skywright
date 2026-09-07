package de.zorro909.skywright.backend.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

class TransferObjectsTest {

	@Test
	void verifiesAndCopiesACompleteStream() throws Exception {
		byte[] bytes = new byte[3 * 1024 * 1024 + 17];
		new java.util.Random(42).nextBytes(bytes);
		var copy = new ByteArrayOutputStream();
		TransferObjects.verify(new ByteArrayInputStream(bytes), bytes.length, digest(bytes), copy);
		assertThat(copy.toByteArray()).isEqualTo(bytes);
	}

	@Test
	void rejectsTruncationAndCorruption() throws Exception {
		byte[] bytes = { 1, 2, 3 };
		String digest = digest(bytes);
		assertThatThrownBy(() -> TransferObjects.verify(new ByteArrayInputStream(new byte[] { 1, 2 }), 3, digest,
				OutputStream.nullOutputStream()))
			.isInstanceOf(TransferObjects.IntegrityMismatch.class);
		assertThatThrownBy(() -> TransferObjects.verify(new ByteArrayInputStream(new byte[] { 1, 2, 4 }), 3, digest,
				OutputStream.nullOutputStream()))
			.isInstanceOf(TransferObjects.IntegrityMismatch.class);
	}

	@Test
	void readsOnlyOneByteBeyondTheDeclaredLengthFromAnEndlessSource() {
		int[] consumed = { 0 };
		InputStream source = new InputStream() {
			@Override
			public int read() {
				consumed[0]++;
				return 0;
			}
		};
		assertThatThrownBy(() -> TransferObjects.verify(source, 10, "0".repeat(64), OutputStream.nullOutputStream()))
			.isInstanceOf(TransferObjects.IntegrityMismatch.class);
		assertThat(consumed[0]).isEqualTo(11);
	}

	@Test
	void preservesInterruptionWithoutReadingMoreBytes() {
		Thread.currentThread().interrupt();
		try {
			assertThatThrownBy(() -> TransferObjects.verify(InputStream.nullInputStream(), 0, "0".repeat(64),
					OutputStream.nullOutputStream()))
				.isInstanceOf(InterruptedIOException.class);
			assertThat(Thread.currentThread().isInterrupted()).isTrue();
		}
		finally {
			Thread.interrupted();
		}
	}

	private static String digest(byte[] bytes) throws Exception {
		return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
	}

}
