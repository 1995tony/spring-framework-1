/*
 * Copyright 2002-2017 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.http.client;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URI;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;

import org.springframework.core.task.AsyncListenableTaskExecutor;
import org.springframework.http.HttpMethod;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.FileCopyUtils;

/**
 * {@link ClientHttpRequestFactory} implementation that uses standard JDK facilities.
 *
 * @author Arjen Poutsma
 * @author Juergen Hoeller
 * @see java.net.HttpURLConnection
 * @see HttpComponentsClientHttpRequestFactory
 * @since 3.0
 */
@SuppressWarnings("deprecation")
public class SimpleClientHttpRequestFactory implements ClientHttpRequestFactory, AsyncClientHttpRequestFactory {

	private static final int DEFAULT_CHUNK_SIZE = 4096;


	@Nullable
	private Proxy proxy; // java.net.proxy

	private boolean bufferRequestBody = true; // 默認會緩衝 body

	private int chunkSize = DEFAULT_CHUNK_SIZE;

	// URLConnection's connect timeout (in milliseounds).
	// 若為0, 表示永不超時
	private int connectTimeout = -1;

	// 超時規則, 同上
	private int readTimeout = -1;

	// set if underlying URLConnection can be set to 'output streaming' mode
	private boolean outputStreaming = true;

	// 異步的時候需要
	@Nullable
	private AsyncListenableTaskExecutor taskExecutor;


	/**
	 * Set the {@link Proxy} to use for this request factory.
	 */
	public void setProxy(Proxy proxy) {
		this.proxy = proxy;
	}

	/**
	 * Indicate whether this request factory should buffer the
	 * {@linkplain ClientHttpRequest#getBody() request body} internally.
	 * <p>Default is {@code true}. When sending large amounts of data via POST or PUT,
	 * it is recommended to change this property to {@code false}, so as not to run
	 * out of memory. This will result in a {@link ClientHttpRequest} that either
	 * streams directly to the underlying {@link HttpURLConnection} (if the
	 * {@link org.springframework.http.HttpHeaders#getContentLength() Content-Length}
	 * is known in advance), or that will use "Chunked transfer encoding"
	 * (if the {@code Content-Length} is not known in advance).
	 *
	 * @see #setChunkSize(int)
	 * @see HttpURLConnection#setFixedLengthStreamingMode(int)
	 */
	public void setBufferRequestBody(boolean bufferRequestBody) {
		this.bufferRequestBody = bufferRequestBody;
	}

	/**
	 * Set the number of bytes to write in each chunk when not buffering request
	 * bodies locally.
	 * <p>Note that this parameter is only used when
	 * {@link #setBufferRequestBody(boolean) bufferRequestBody} is set to {@code false},
	 * and the {@link org.springframework.http.HttpHeaders#getContentLength() Content-Length}
	 * is not known in advance.
	 *
	 * @see #setBufferRequestBody(boolean)
	 */
	public void setChunkSize(int chunkSize) {
		this.chunkSize = chunkSize;
	}

	/**
	 * Set the underlying URLConnection's connect timeout (in milliseconds).
	 * A timeout value of 0 specifies an infinite timeout.
	 * <p>Default is the system's default timeout.
	 *
	 * @see URLConnection#setConnectTimeout(int)
	 */
	public void setConnectTimeout(int connectTimeout) {
		this.connectTimeout = connectTimeout;
	}

	/**
	 * Set the underlying URLConnection's read timeout (in milliseconds).
	 * A timeout value of 0 specifies an infinite timeout.
	 * <p>Default is the system's default timeout.
	 *
	 * @see URLConnection#setReadTimeout(int)
	 */
	public void setReadTimeout(int readTimeout) {
		this.readTimeout = readTimeout;
	}

	/**
	 * Set if the underlying URLConnection can be set to 'output streaming' mode.
	 * Default is {@code true}.
	 * <p>When output streaming is enabled, authentication and redirection cannot be handled automatically.
	 * If output streaming is disabled, the {@link HttpURLConnection#setFixedLengthStreamingMode} and
	 * {@link HttpURLConnection#setChunkedStreamingMode} methods of the underlying connection will never
	 * be called.
	 *
	 * @param outputStreaming if output streaming is enabled
	 */
	public void setOutputStreaming(boolean outputStreaming) {
		this.outputStreaming = outputStreaming;
	}

	/**
	 * Set the task executor for this request factory. Setting this property is required
	 * for {@linkplain #createAsyncRequest(URI, HttpMethod) creating asynchronous requests}.
	 *
	 * @param taskExecutor the task executor
	 */
	public void setTaskExecutor(AsyncListenableTaskExecutor taskExecutor) {
		this.taskExecutor = taskExecutor;
	}


	@Override
	public ClientHttpRequest createRequest(URI uri, HttpMethod httpMethod) throws IOException {

		// 打開一個 HttpURLConnection
		HttpURLConnection connection = openConnection(uri.toURL(), this.proxy);
		// 設置超時時間, 請求方法等一些參數到 Connection
		prepareConnection(connection, httpMethod.name());

		// SimpleBufferingClientHttpRequest 的 execute 方法最終使用的是 connection.connect();
		// 然後從 connection 中得到響應碼, 響應體
		if (this.bufferRequestBody) {
			return new SimpleBufferingClientHttpRequest(connection, this.outputStreaming);
		} else {
			return new SimpleStreamingClientHttpRequest(connection, this.chunkSize, this.outputStreaming);
		}
	}

	/**
	 * {@inheritDoc}
	 * <p>Setting the {@link #setTaskExecutor taskExecutor} property is required before calling this method.
	 */
	// 在線程池裡, 異步完成請求
	@Override
	public AsyncClientHttpRequest createAsyncRequest(URI uri, HttpMethod httpMethod) throws IOException {
		Assert.state(this.taskExecutor != null, "Asynchronous execution requires TaskExecutor to be set");

		HttpURLConnection connection = openConnection(uri.toURL(), this.proxy);
		prepareConnection(connection, httpMethod.name());

		if (this.bufferRequestBody) {
			return new SimpleBufferingAsyncClientHttpRequest(
					connection, this.outputStreaming, this.taskExecutor);
		} else {
			return new SimpleStreamingAsyncClientHttpRequest(
					connection, this.chunkSize, this.outputStreaming, this.taskExecutor);
		}
	}

	/**
	 * Opens and returns a connection to the given URL.
	 * <p>The default implementation uses the given {@linkplain #setProxy(java.net.Proxy) proxy} -
	 * if any - to open a connection.
	 *
	 * @param url   the URL to open a connection to
	 * @param proxy the proxy to use, may be {@code null}
	 * @return the opened connection
	 * @throws IOException in case of I/O errors
	 */
	protected HttpURLConnection openConnection(URL url, @Nullable Proxy proxy) throws IOException {
		URLConnection urlConnection = (proxy != null ? url.openConnection(proxy) : url.openConnection());
		if (!HttpURLConnection.class.isInstance(urlConnection)) {
			throw new IllegalStateException("HttpURLConnection required for [" + url + "] but got: " + urlConnection);
		}
		return (HttpURLConnection) urlConnection;
	}

	/**
	 * Template method for preparing the given {@link HttpURLConnection}.
	 * <p>The default implementation prepares the connection for input and output, and sets the HTTP method.
	 *
	 * @param connection the connection to prepare
	 * @param httpMethod the HTTP request method ({@code GET}, {@code POST}, etc.)
	 * @throws IOException in case of I/O errors
	 */
	protected void prepareConnection(HttpURLConnection connection, String httpMethod) throws IOException {
		if (this.connectTimeout >= 0) {
			connection.setConnectTimeout(this.connectTimeout);
		}
		if (this.readTimeout >= 0) {
			connection.setReadTimeout(this.readTimeout);
		}

		connection.setDoInput(true);

		if ("GET".equals(httpMethod)) {
			connection.setInstanceFollowRedirects(true);
		} else {
			connection.setInstanceFollowRedirects(false);
		}

		if ("POST".equals(httpMethod) || "PUT".equals(httpMethod) ||
				"PATCH".equals(httpMethod) || "DELETE".equals(httpMethod)) {
			connection.setDoOutput(true);
		} else {
			connection.setDoOutput(false);
		}

		connection.setRequestMethod(httpMethod);
	}

	public static void main(String[] args) throws IOException {
		SimpleClientHttpRequestFactory clientFactory = new SimpleClientHttpRequestFactory();

		// ConnectionTimeout 只有在網路正常的情況下才有效, 因此兩個一般都設置
		clientFactory.setConnectTimeout(5000); // 建立連接的超時時間 5秒
		clientFactory.setReadTimeout(5000); // 傳送數據的超時時間 (在網路抖動的時候, 這個參數很有效)

		ClientHttpRequest client = clientFactory.createRequest(URI.create("https://baudy.com"), HttpMethod.GET);
		// 發送請求
		ClientHttpResponse response = client.execute();
		System.out.println(response.getStatusCode());
		System.out.println(response.getStatusText());
		System.out.println(response.getHeaders());

		// 返回內容 是個 InputStream
		byte[] bytes = FileCopyUtils.copyToByteArray(response.getBody());
		System.out.println(new String(bytes, StandardCharsets.UTF_8)); // 百度首頁的 html

	}
}
