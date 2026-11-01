package org.slackerdb.dbserver.service;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 把同一个 key 的并发加载合并成一次（single-flight / 防缓存击穿）。
 *
 * <p>缓存集中过期时，如果每个请求都发现"我手里的数据过期了"就各自去查后端，N 个并发请求
 * 会在同一瞬间打 N 次数据库——这就是缓存击穿。这里把同一 key 的并发加载串起来：第一个调用者
 * （leader）真正执行 {@link Loader}，其余调用者（follower）阻塞等待，拿到 leader 的同一份结果
 * 后直接返回，不再重复加载。</p>
 *
 * <p>语义要点：</p>
 * <ul>
 *   <li><b>失败共享</b>：leader 抛异常时，等待者收到同一个异常，不会因为失败而瞬间重试打库；</li>
 *   <li><b>不缓存失败</b>：异常只影响这一批并发调用，后续请求会重新选举 leader 再试；</li>
 *   <li><b>等待有上限</b>：等待者最多等 {@code waitTimeoutMs}，超时抛 {@link TimeoutException}
 *       由调用方决定降级策略（例如自己直接加载），避免 leader 卡死时无限期挂住请求线程；</li>
 *   <li><b>结果共享</b>：leader 与所有等待者拿到的是同一个 {@code value} 对象。</li>
 * </ul>
 *
 * <p>本类只做并发协调，不感知缓存、TTL 与业务语义；命中/过期判断仍由调用方完成。</p>
 *
 * @param <K> key 类型
 * @param <V> 加载结果类型
 */
final class SingleFlight<K, V> {

    /** 真正加载数据的动作；可抛受检异常，异常会原样传递给等待者。 */
    @FunctionalInterface
    interface Loader<V> {
        V load() throws Exception;
    }

    /**
     * 一次加载的结果。
     *
     * @param value          leader 与所有等待者共享的加载结果
     * @param loadedByCaller 本次调用是否就是真正执行 {@link Loader} 的 leader
     */
    record Result<V>(V value, boolean loadedByCaller) {}

    /** 当前正在执行的加载。key 只在加载期间存在，加载结束（无论成败）即移除。 */
    private final ConcurrentHashMap<K, CompletableFuture<V>> inFlight = new ConcurrentHashMap<>();

    /**
     * 加载 {@code key}：leader 执行 {@code loader}，follower 等待并共享结果。
     *
     * @param key           合并粒度，同一 key 的并发调用只会执行一次 loader
     * @param waitTimeoutMs follower 最长等待时间（毫秒），{@code <= 0} 表示不等待
     * @param loader        leader 执行的加载动作
     * @return 加载结果及"本次调用是否为 leader"的标记
     * @throws Exception leader 抛出的异常原样抛出；等待超时抛 {@link TimeoutException}
     */
    Result<V> load(K key, long waitTimeoutMs, Loader<V> loader) throws Exception {
        CompletableFuture<V> mine = new CompletableFuture<>();
        CompletableFuture<V> running = inFlight.putIfAbsent(key, mine);

        if (running == null) {
            // 我是 leader：独占执行，结束时唤醒所有等待者
            try {
                V value = loader.load();
                mine.complete(value);
                return new Result<>(value, true);
            } catch (Throwable failure) {
                // 先传播给等待者，再把失败原样抛给自己
                mine.completeExceptionally(failure);
                throw propagate(failure);
            } finally {
                // 只移除自己的 future，避免误删后来者的登记
                inFlight.remove(key, mine);
            }
        }

        // 我是 follower：等待 leader 的结果，不再打后端
        try {
            return new Result<>(running.get(waitTimeoutMs, TimeUnit.MILLISECONDS), false);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            throw propagate(cause == null ? e : cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw e;
        } catch (TimeoutException e) {
            TimeoutException timeout =
                    new TimeoutException("Timed out after " + waitTimeoutMs + " ms waiting for an in-flight load of key: " + key);
            timeout.initCause(e);
            throw timeout;
        }
    }

    /** 当前正在进行的加载数量（观测/测试用）。 */
    int inFlightCount() {
        return inFlight.size();
    }

    /**
     * 把加载过程中抛出的 {@link Throwable} 还原成可抛出的异常。
     *
     * <p>{@link Loader} 只声明了 {@link Exception}，但实现里可能抛出 {@link Error}；
     * 这里保持 Error 原样上抛，其它非受检异常包装成 RuntimeException。</p>
     */
    private static Exception propagate(Throwable failure) {
        if (failure instanceof Exception exception) {
            return exception;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        return new RuntimeException(failure);
    }
}
