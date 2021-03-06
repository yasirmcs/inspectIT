package rocks.inspectit.shared.cs.storage.nio.stream;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.mockito.Matchers.anyLong;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;

import org.mockito.Matchers;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import rocks.inspectit.shared.all.storage.nio.ByteBufferProvider;
import rocks.inspectit.shared.cs.indexing.storage.IStorageDescriptor;
import rocks.inspectit.shared.cs.indexing.storage.impl.StorageDescriptor;
import rocks.inspectit.shared.cs.storage.StorageData;
import rocks.inspectit.shared.cs.storage.StorageManager;
import rocks.inspectit.shared.cs.storage.nio.WriteReadCompletionRunnable;
import rocks.inspectit.shared.cs.storage.nio.read.ReadingChannelManager;

/**
 * Testing of the {@link ExtendedByteBufferInputStream} class.
 *
 * @author Ivan Senic
 *
 */
@SuppressWarnings("PMD")
public class ExtendedByteBufferInputStreamTest {

	private static final int NUMBER_OF_BUFFERS = 2;

	/**
	 * Class under test.
	 */
	private ExtendedByteBufferInputStream inputStream;

	@Mock
	private ReadingChannelManager readingChannelManager;

	@Mock
	private ByteBufferProvider byteBufferProvider;

	@Mock
	private StorageManager storageManager;

	@Mock
	private StorageData storageData;

	/**
	 * Executor service needed for the handler. Can not be mocked.
	 */
	private ScheduledExecutorService executorService = Executors.newScheduledThreadPool(1);

	/**
	 * Init.
	 */
	@BeforeMethod
	public void init() {
		MockitoAnnotations.initMocks(this);
		inputStream = new ExtendedByteBufferInputStream(storageData, null, NUMBER_OF_BUFFERS);
		inputStream.setByteBufferProvider(byteBufferProvider);
		inputStream.setReadingChannelManager(readingChannelManager);
		inputStream.setStorageManager(storageManager);
		inputStream.setExecutorService(executorService);
		when(storageManager.getChannelPath(eq(storageData), Matchers.<IStorageDescriptor> anyObject())).thenReturn(Paths.get("/"));
	}

	/**
	 * Tests reading of random size.
	 *
	 * @throws IOException
	 */
	@Test(invocationCount = 50)
	public void read() throws IOException {
		Random random = new Random();
		final int readSize = random.nextInt(8096);
		final int bufferSize = 1024;
		final byte[] array = new byte[readSize];
		random.nextBytes(array);

		when(byteBufferProvider.acquireByteBuffer()).thenAnswer(new Answer<ByteBuffer>() {
			@Override
			public ByteBuffer answer(InvocationOnMock invocation) throws Throwable {
				return ByteBuffer.allocateDirect(bufferSize);
			}
		});

		List<IStorageDescriptor> descriptors = new ArrayList<>();
		long totalInDescriptors = 0;
		while (totalInDescriptors < readSize) {
			long descriptorSize = Math.min(random.nextInt(2048), readSize - totalInDescriptors);
			IStorageDescriptor storageDescriptor = mock(StorageDescriptor.class);
			when(storageDescriptor.getPosition()).thenReturn(totalInDescriptors);
			when(storageDescriptor.getSize()).thenReturn(descriptorSize);
			totalInDescriptors += descriptorSize;
			descriptors.add(storageDescriptor);
		}

		doAnswer(new Answer<Future<?>>() {
			@Override
			public Future<?> answer(InvocationOnMock invocation) throws Throwable {
				Object[] args = invocation.getArguments();
				ByteBuffer byteBuffer = (ByteBuffer) args[0];
				long position = (long) args[1];
				long size = (long) args[2];
				WriteReadCompletionRunnable writeReadCompletionRunnable = (WriteReadCompletionRunnable) args[4];

				byteBuffer.put(array, (int) position, (int) size);
				byteBuffer.flip();

				writeReadCompletionRunnable.setAttemptedWriteReadSize(size);
				writeReadCompletionRunnable.setAttemptedWriteReadPosition(position);
				writeReadCompletionRunnable.markSuccess();
				writeReadCompletionRunnable.run();

				return mock(Future.class);
			}
		}).when(readingChannelManager).read(Matchers.<ByteBuffer> anyObject(), anyLong(), anyLong(), Matchers.<Path> anyObject(), Matchers.<WriteReadCompletionRunnable> anyObject());

		inputStream.setDescriptors(descriptors);
		inputStream.prepare();

		byte[] bytes = new byte[readSize];
		// do read in series
		int alreadyRead = 0;
		while (alreadyRead < readSize) {
			int actuallyRead = inputStream.read(bytes, alreadyRead, Math.min(random.nextInt(512), readSize - alreadyRead));
			alreadyRead += actuallyRead;
		}
		assertThat(inputStream.hasRemaining(), is(false));
		inputStream.close();

		try {
			assertThat(bytes, is(equalTo(array)));
		} catch (Throwable e) {
			e.printStackTrace();
		}

		verify(byteBufferProvider, times(NUMBER_OF_BUFFERS)).releaseByteBuffer(Matchers.<ByteBuffer> anyObject());
	}
}
