package com.overdrive.app.ui.model;

/**
 * Truthful toolbar/dashboard state derived from tunnel process state and URL.
 *
 * <p>Controller URL values may contain a last-known URL restored from
 * preferences, so a URL alone is not proof that its tunnel is online.
 */
public final class TunnelDisplayPolicy {
    public enum Kind {
        HIDDEN,
        STARTING_ZROK,
        STARTING_CLOUDFLARED,
        STARTING_TAILSCALE,
        WAITING_FOR_URL,
        ONLINE
    }

    public static final class Result {
        private final Kind kind;
        private final String url;

        private Result(Kind kind, String url) {
            this.kind = kind;
            this.url = url;
        }

        public Kind getKind() {
            return kind;
        }

        public String getUrl() {
            return url;
        }

        public boolean isVisible() {
            return kind != Kind.HIDDEN;
        }

        public boolean isOnline() {
            return kind == Kind.ONLINE && url != null;
        }
    }

    private TunnelDisplayPolicy() {
    }

    public static Result resolve(String zrokUrl,
                                 String cloudflaredUrl,
                                 String tailscaleUrl,
                                 DaemonStatus zrokStatus,
                                 DaemonStatus cloudflaredStatus,
                                 DaemonStatus tailscaleStatus) {
        if (isActiveUrl(zrokUrl, zrokStatus)) {
            return new Result(Kind.ONLINE, zrokUrl);
        }
        if (isActiveUrl(cloudflaredUrl, cloudflaredStatus)) {
            return new Result(Kind.ONLINE, cloudflaredUrl);
        }
        if (isActiveUrl(tailscaleUrl, tailscaleStatus)) {
            return new Result(Kind.ONLINE, tailscaleUrl);
        }

        if (zrokStatus == DaemonStatus.STARTING) {
            return new Result(Kind.STARTING_ZROK, null);
        }
        if (cloudflaredStatus == DaemonStatus.STARTING) {
            return new Result(Kind.STARTING_CLOUDFLARED, null);
        }
        if (tailscaleStatus == DaemonStatus.STARTING) {
            return new Result(Kind.STARTING_TAILSCALE, null);
        }

        if (zrokStatus == DaemonStatus.RUNNING
                || cloudflaredStatus == DaemonStatus.RUNNING
                || tailscaleStatus == DaemonStatus.RUNNING) {
            return new Result(Kind.WAITING_FOR_URL, null);
        }
        return new Result(Kind.HIDDEN, null);
    }

    public static boolean isActiveUrl(String url, DaemonStatus status) {
        return status == DaemonStatus.RUNNING
                && url != null
                && !url.trim().isEmpty();
    }
}
