package de.zorro909.skywright.backend.runstore;

import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.async.AsyncResponseTransformer;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;

/** Completes only after the bounded body, so SDK timeouts cover verification. */
public class BoundedS3Body implements AsyncResponseTransformer<GetObjectResponse, ResponseBytes<GetObjectResponse>> {

	private final int limit;

	private final java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();

	private final java.util.concurrent.CompletableFuture<ResponseBytes<GetObjectResponse>> result = new java.util.concurrent.CompletableFuture<>();

	private volatile org.reactivestreams.Subscription subscription;

	private GetObjectResponse response;

	public BoundedS3Body(int limit) {
		this.limit = limit;
	}

	@Override
	public java.util.concurrent.CompletableFuture<ResponseBytes<GetObjectResponse>> prepare() {
		result.whenComplete((value, failure) -> {
			if (failure != null && subscription != null)
				subscription.cancel();
		});
		return result;
	}

	@Override
	public void onResponse(GetObjectResponse value) {
		response = value;
		if (value.contentLength() == null || value.contentLength() < 0 || value.contentLength() > limit)
			exceptionOccurred(new IllegalStateException("Invalid object body size"));
	}

	@Override
	public void onStream(software.amazon.awssdk.core.async.SdkPublisher<java.nio.ByteBuffer> publisher) {
		publisher.subscribe(new org.reactivestreams.Subscriber<java.nio.ByteBuffer>() {
			public void onSubscribe(org.reactivestreams.Subscription value) {
				subscription = value;
				if (result.isDone())
					value.cancel();
				else
					value.request(1);
			}

			public void onNext(java.nio.ByteBuffer value) {
				if (result.isDone())
					return;
				if (value.remaining() > limit - bytes.size()) {
					exceptionOccurred(new IllegalStateException("Oversized object body"));
					return;
				}
				byte[] chunk = new byte[value.remaining()];
				value.get(chunk);
				bytes.writeBytes(chunk);
				subscription.request(1);
			}

			public void onError(Throwable failure) {
				exceptionOccurred(failure);
			}

			public void onComplete() {
				if (response == null || bytes.size() != response.contentLength())
					exceptionOccurred(new IllegalStateException("Truncated object body"));
				else
					result.complete(ResponseBytes.fromByteArray(response, bytes.toByteArray()));
			}
		});
	}

	@Override
	public void exceptionOccurred(Throwable failure) {
		result.completeExceptionally(failure);
	}

}
