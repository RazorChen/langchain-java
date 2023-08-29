/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hw.openai;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hw.openai.entity.chat.ChatCompletion;
import com.hw.openai.entity.chat.ChatCompletionResp;
import com.hw.openai.entity.completions.Completion;
import com.hw.openai.entity.completions.CompletionResp;
import com.hw.openai.entity.embeddings.Embedding;
import com.hw.openai.entity.embeddings.EmbeddingResp;
import com.hw.openai.entity.models.Model;
import com.hw.openai.entity.models.ModelResp;
import com.hw.openai.service.AzureOpenAiService;
import com.hw.openai.utils.ProxyUtils;
import lombok.Builder;
import lombok.Data;
import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.logging.HttpLoggingInterceptor;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import retrofit2.Retrofit;
import retrofit2.adapter.rxjava2.RxJava2CallAdapterFactory;
import retrofit2.converter.jackson.JacksonConverterFactory;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Represents a client for interacting with the OpenAI API.
 *
 * @author HamaWhite
 */
@Data
@Builder
public class AzureOpenAiClient {

    private static final Logger LOG = LoggerFactory.getLogger(AzureOpenAiClient.class);

    private String baseUrl;

    private String azureApiKey;

    private String openaiProxy;

    /**
     * the username for proxy authentication (optional)
     */
    private String proxyUsername;

    /**
     * the password for proxy authentication (optional)
     */
    private String proxyPassword;

    /**
     * Timeout for requests to OpenAI completion API. Default is 16 seconds.
     */
    @Builder.Default
    protected long requestTimeout = 16;

    private List<Interceptor> interceptorList;

    private AzureOpenAiService service;

    private OkHttpClient httpClient;

    /**
     * Initializes the OpenAiClient instance.
     *
     * @return the initialized OpenAiClient instance
     */
    public AzureOpenAiClient init() {
        baseUrl = getOrEnvOrDefault(baseUrl, "OPENAI_API_BASE", "https://dev-gpt-demo.openai.azure.com/openai/deployments/gpt-35-turbo-16k/");
        openaiProxy = getOrEnvOrDefault(openaiProxy, "OPENAI_PROXY");

        OkHttpClient.Builder httpClientBuilder = new OkHttpClient.Builder()
                .connectTimeout(requestTimeout, TimeUnit.SECONDS)
                .readTimeout(requestTimeout, TimeUnit.SECONDS)
                .writeTimeout(requestTimeout, TimeUnit.SECONDS)
                .callTimeout(requestTimeout, TimeUnit.SECONDS);

        // If azureApiKey is not null, create azure openai client
        httpClientBuilder.addInterceptor(chain -> {
            Request request = chain.request().newBuilder()
                    .header("Content-Type", "application/json")
                    .header("api-key", azureApiKey)
                    .build();

            return chain.proceed(request);
        });

        // Add HttpLogging interceptor
        HttpLoggingInterceptor loggingInterceptor = new HttpLoggingInterceptor(LOG::debug);
        loggingInterceptor.setLevel(HttpLoggingInterceptor.Level.BODY);
        httpClientBuilder.addInterceptor(loggingInterceptor);
        if (this.interceptorList != null) {
            this.interceptorList.forEach(httpClientBuilder::addInterceptor);
        }

        if (StringUtils.isNotEmpty(openaiProxy)) {
            httpClientBuilder.proxy(ProxyUtils.http(openaiProxy, proxyUsername, proxyPassword));
        }
        httpClient = httpClientBuilder.build();

        // Used for automatic discovery and registration of Jackson modules
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.findAndRegisterModules();
        // Ignore unknown fields
        objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

        Retrofit retrofit = new Retrofit.Builder()
                .baseUrl(baseUrl)
                .addCallAdapterFactory(RxJava2CallAdapterFactory.create())
                .addConverterFactory(JacksonConverterFactory.create(objectMapper))
                .client(httpClient)
                .build();

        this.service = retrofit.create(AzureOpenAiService.class);
        return this;
    }

    /**
     * Closes the HttpClient connection pool.
     */
    public void close() {
        // Cancel all ongoing requests
        httpClient.dispatcher().cancelAll();

        // Shut down the connection pool (if any)
        httpClient.connectionPool().evictAll();
        httpClient.dispatcher().executorService().shutdown();
    }

    private String getOrEnvOrDefault(String originalValue, String envKey, String... defaultValue) {
        if (StringUtils.isNotEmpty(originalValue)) {
            return originalValue;
        }
        String envValue = System.getenv(envKey);
        if (StringUtils.isNotEmpty(envValue)) {
            return envValue;
        }
        if (defaultValue.length > 0) {
            return defaultValue[0];
        }
        return null;
    }

    /**
     * Lists the currently available models, and provides basic information about each one
     * such as the owner and availability.
     *
     * @return the response containing the list of available models
     */
    public ModelResp listModels() {
        return service.listModels().blockingGet();
    }

    /**
     * Retrieves a model instance, providing basic information about the model such as the owner and permissions.
     *
     * @param model the ID of the model to retrieve
     * @return the retrieved model
     */
    public Model retrieveModel(String model) {
        return service.retrieveModel(model).blockingGet();
    }

    /**
     * Creates a completion for the provided prompt and parameters.
     *
     * @param completion the completion object containing the prompt and parameters
     * @return the generated completion text
     */
    public String completion(Completion completion) {
        CompletionResp response = service.completion(completion).blockingGet();

        String text = response.getChoices().get(0).getText();
        return StringUtils.trim(text);
    }

    /**
     * Creates a completion for the provided prompt and parameters.
     *
     * @param completion the completion object containing the prompt and parameters
     * @return the completion response
     */
    public CompletionResp create(Completion completion) {
        return service.completion(completion).blockingGet();
    }

    /**
     * Creates a model response for the given chat conversation.
     *
     * @param chatCompletion the chat completion object containing the conversation
     * @return the generated model response text
     */
    public String chatCompletion(String deployment, String apiVersion, ChatCompletion chatCompletion) {
        ChatCompletionResp response = service.chatCompletion(deployment, apiVersion, chatCompletion).blockingGet();

        String content = response.getChoices().get(0).getMessage().getContent();
        return StringUtils.trim(content);
    }

    /**
     * Creates a model response for the given chat conversation.
     *
     * @param chatCompletion the chat completion object containing the conversation
     * @return the chat completion response
     */
    public ChatCompletionResp create(String deployment, String apiVersion, ChatCompletion chatCompletion) {
        return service.chatCompletion(deployment, apiVersion, chatCompletion).blockingGet();
    }

    /**
     * Creates an embedding vector representing the input text.
     *
     * @param embedding The Embedding object containing the input text.
     * @return The embedding vector response.
     */
    public EmbeddingResp embedding(Embedding embedding) {
        return service.embedding(embedding).blockingGet();
    }
}