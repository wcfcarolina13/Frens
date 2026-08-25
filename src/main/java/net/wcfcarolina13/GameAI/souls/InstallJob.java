package net.wcfcarolina13.GameAI.souls;

/**
 * Progress state for one background install/download, owned by the service that runs it
 * (static, screen-independent) so an installer screen can be closed and reopened and
 * simply re-attach to the running job — and so a second concurrent start of the same
 * job is impossible (services guard with a compare-and-set on their active-job ref).
 *
 * <p>All fields are volatile: the worker thread writes, the render thread reads.
 */
public final class InstallJob {

    private final String description;
    private volatile String stage = "Starting…";
    private volatile long bytesDone;
    private volatile long bytesTotal;
    private volatile boolean finished;
    private volatile String error; // non-null after finish = failure message

    public InstallJob(String description) {
        this.description = description;
    }

    public String description() {
        return description;
    }

    public String stage() {
        return stage;
    }

    public long bytesDone() {
        return bytesDone;
    }

    public long bytesTotal() {
        return bytesTotal;
    }

    public boolean finished() {
        return finished;
    }

    public String error() {
        return error;
    }

    public void progress(String stage, long done, long total) {
        this.stage = stage;
        this.bytesDone = done;
        this.bytesTotal = total;
    }

    public void finishOk() {
        this.finished = true;
    }

    public void finishFailed(String message) {
        this.error = message == null ? "failed" : message;
        this.finished = true;
    }
}
