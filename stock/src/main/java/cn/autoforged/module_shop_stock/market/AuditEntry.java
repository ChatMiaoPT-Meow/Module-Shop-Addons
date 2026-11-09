package cn.autoforged.module_shop_stock.market;

public final class AuditEntry {
    public final long time;
    public final String actor;
    public final String action;
    public final String detail;

    public AuditEntry(long time, String actor, String action, String detail) {
        this.time = time;
        this.actor = actor == null ? "?" : actor;
        this.action = action == null ? "" : action;
        this.detail = detail == null ? "" : detail;
    }
}
