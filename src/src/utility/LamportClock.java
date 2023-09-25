package utility;

public class LamportClock {
    private int timestamp;

    public LamportClock() {
        timestamp = 0;
    }

    public int getTimestamp() {
        timestamp += 1;
        return timestamp;
    }

    public void setTimestamp(int timestamp) {
        this.timestamp = Math.max(timestamp, this.timestamp) + 1;
    }
}
