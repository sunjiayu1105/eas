public final class ConsumeIdGenerator {
    private static final int WORKER_ID_SHIFT = 12;
    private static final int TIMESTAMP_SHIFT = 22;
    private static final long MAX_WORKER_ID = (1L << 10) - 1;
    private static final long SEQUENCE_MASK = (1L << 12) - 1;
    private static final long MAX_TIMESTAMP = (1L << 41) - 1;

    private final long epochMillis;
    private final long workerId;
    private long lastTimestamp = -1L;
    private long sequence = 0L;

    public ConsumeIdGenerator(long epochMillis, long workerId) {
        if (epochMillis < 0 || epochMillis > System.currentTimeMillis()) {
            throw new IllegalArgumentException("epochMillis 必须是已到达的非负时间");
        }
        if (workerId < 0 || workerId > MAX_WORKER_ID) {
            throw new IllegalArgumentException("workerId 必须在 0～1023 之间");
        }
        this.epochMillis = epochMillis;
        this.workerId = workerId;
    }

    public synchronized long nextId() {
        long now = System.currentTimeMillis();
        checkClock(now);
        long nextSequence = 0L;

        if (now == lastTimestamp) {
            nextSequence = (sequence + 1) & SEQUENCE_MASK;
            if (nextSequence == 0L) {
                now = waitNextMillis();
            }
        }

        long timestampPart = now - epochMillis;
        if (timestampPart < 0 || timestampPart > MAX_TIMESTAMP) {
            throw new IllegalStateException("timestampPart 超出 41 位范围");
        }

        // 校验通过才提交状态，异常不会提前消耗或重置序列。
        lastTimestamp = now;
        sequence = nextSequence;
        return (timestampPart << TIMESTAMP_SHIFT)
                | (workerId << WORKER_ID_SHIFT)
                | sequence;
    }

    private void checkClock(long now) {
        if (now < lastTimestamp) {
            throw new IllegalStateException("系统时间回拨，停止生成 ID");
        }
    }

    private long waitNextMillis() {
        while (true) {
            long now = System.currentTimeMillis();
            checkClock(now);
            if (now > lastTimestamp) {
                return now;
            }
            try {
                Thread.sleep(1L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("等待下一毫秒时被中断", e);
            }
        }
    }
}