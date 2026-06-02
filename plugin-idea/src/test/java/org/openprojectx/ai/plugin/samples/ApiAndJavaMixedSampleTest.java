package org.openprojectx.ai.plugin.samples;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ApiAndJavaMixedSampleTest {

    @Mock
    private HttpClient httpClient; // 模拟的HTTP客户端

    @Mock
    private HttpResponse<String> httpResponse; // 模拟的HTTP响应

    private ApiAndJavaMixedSample sample; // 被测试的实例

    @BeforeEach
    void setUp() {
        // 初始化被测试实例，注入模拟的HTTP客户端
        sample = new ApiAndJavaMixedSample(httpClient);
    }

    @Test
    void fetchUserStatusCode_shouldReturnStatusCode_whenRequestSucceeds() throws Exception {
        // 测试：当HTTP请求成功时，应返回状态码
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(httpResponse);
        when(httpResponse.statusCode()).thenReturn(200);

        int statusCode = sample.fetchUserStatusCode("https://api.example.com", "user123");

        assertEquals(200, statusCode);
        verify(httpClient).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    @Test
    void fetchUserStatusCode_shouldThrowIllegalStateException_whenIOExceptionOccurs() throws Exception {
        // 测试：当发生IO异常时，应抛出IllegalStateException
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenThrow(new IOException("Connection failed"));

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> sample.fetchUserStatusCode("https://api.example.com", "user123"));
        assertTrue(exception.getMessage().contains("Failed to fetch user status code"));
    }

    @Test
    void fetchUserStatusCode_shouldThrowIllegalStateException_whenInterruptedExceptionOccurs() throws Exception {
        // 测试：当发生中断异常时，应抛出IllegalStateException
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenThrow(new InterruptedException("Interrupted"));

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> sample.fetchUserStatusCode("https://api.example.com", "user123"));
        assertTrue(exception.getMessage().contains("Failed to fetch user status code"));
    }

    @Test
    void fetchOrderBody_shouldReturnBody_whenRequestSucceeds() throws Exception {
        // 测试：当HTTP请求成功时，应返回响应体
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(httpResponse);
        when(httpResponse.body()).thenReturn("{\"orderId\": \"order456\"}");

        String body = sample.fetchOrderBody("https://api.example.com", "order456");

        assertEquals("{\"orderId\": \"order456\"}", body);
        verify(httpClient).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    @Test
    void fetchOrderBody_shouldThrowIllegalStateException_whenIOExceptionOccurs() throws Exception {
        // 测试：当发生IO异常时，应抛出IllegalStateException
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenThrow(new IOException("Connection failed"));

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> sample.fetchOrderBody("https://api.example.com", "order456"));
        assertTrue(exception.getMessage().contains("Failed to fetch order body"));
    }

    @Test
    void fetchOrderBody_shouldThrowIllegalStateException_whenInterruptedExceptionOccurs() throws Exception {
        // 测试：当发生中断异常时，应抛出IllegalStateException
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenThrow(new InterruptedException("Interrupted"));

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> sample.fetchOrderBody("https://api.example.com", "order456"));
        assertTrue(exception.getMessage().contains("Failed to fetch order body"));
    }

    @Test
    void sum_shouldReturnZero_whenListIsNull() {
        // 测试：当列表为null时，应返回0
        assertEquals(0, sample.sum(null));
    }

    @Test
    void sum_shouldReturnZero_whenListIsEmpty() {
        // 测试：当列表为空时，应返回0
        assertEquals(0, sample.sum(Collections.emptyList()));
    }

    @Test
    void sum_shouldReturnSumOfValues_whenListContainsNonNullValues() {
        // 测试：当列表包含非空值时，应返回所有值的和
        assertEquals(15, sample.sum(Arrays.asList(1, 2, 3, 4, 5)));
    }

    @Test
    void sum_shouldSkipNullValues_whenListContainsNulls() {
        // 测试：当列表包含null值时，应跳过null值并计算非空值的和
        assertEquals(6, sample.sum(Arrays.asList(1, null, 2, null, 3)));
    }

    @Test
    void sum_shouldReturnZero_whenListContainsOnlyNulls() {
        // 测试：当列表只包含null值时，应返回0
        assertEquals(0, sample.sum(Arrays.asList(null, null, null)));
    }

    @Test
    void normalizeName_shouldReturnEmptyString_whenInputIsNull() {
        // 测试：当输入为null时，应返回空字符串
        assertEquals("", sample.normalizeName(null));
    }

    @Test
    void normalizeName_shouldTrimAndLowercase_whenInputHasSpaces() {
        // 测试：当输入包含空格时，应去除首尾空格并将中间多个空格合并为一个，然后转换为小写
        assertEquals("john doe", sample.normalizeName("  John   Doe  "));
    }

    @Test
    void normalizeName_shouldReturnLowercase_whenInputIsAlreadyTrimmed() {
        // 测试：当输入已经去除首尾空格时，应直接转换为小写
        assertEquals("alice", sample.normalizeName("Alice"));
    }

    @Test
    void normalizeName_shouldHandleMultipleSpaces_whenInputHasMultipleSpaces() {
        // 测试：当输入包含多个连续空格时，应合并为一个空格并转换为小写
        assertEquals("a b c", sample.normalizeName("A   B   C"));
    }

    @Test
    void normalizeName_shouldReturnEmptyString_whenInputIsEmpty() {
        // 测试：当输入为空字符串时，应返回空字符串
        assertEquals("", sample.normalizeName(""));
    }

    @Test
    void isVipLevel_shouldReturnTrue_whenScoreIs900() {
        // 测试：当分数为900时，应返回true（VIP等级）
        assertTrue(sample.isVipLevel(900));
    }

    @Test
    void isVipLevel_shouldReturnTrue_whenScoreIsAbove900() {
        // 测试：当分数大于900时，应返回true（VIP等级）
        assertTrue(sample.isVipLevel(1000));
    }

    @Test
    void isVipLevel_shouldReturnFalse_whenScoreIsBelow900() {
        // 测试：当分数小于900时，应返回false（非VIP等级）
        assertFalse(sample.isVipLevel(899));
    }

    @Test
    void isVipLevel_shouldReturnFalse_whenScoreIsNegative() {
        // 测试：当分数为负数时，应返回false（非VIP等级）
        assertFalse(sample.isVipLevel(-100));
    }

    @Test
    void isVipLevel_shouldReturnFalse_whenScoreIsZero() {
        // 测试：当分数为0时，应返回false（非VIP等级）
        assertFalse(sample.isVipLevel(0));
    }
}