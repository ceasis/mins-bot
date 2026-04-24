package com.minsbot.skills.watcher.adapters;

import com.minsbot.skills.watcher.Watcher;

public interface WatcherAdapter {
    String name();
    CheckResult check(Watcher w) throws Exception;

    record CheckResult(String status, String detail) {
        public static CheckResult inStock(String detail) { return new CheckResult("in-stock", detail); }
        public static CheckResult outOfStock(String detail) { return new CheckResult("out-of-stock", detail); }
        public static CheckResult unknown(String detail) { return new CheckResult("unknown", detail); }
        public static CheckResult error(String msg) { return new CheckResult("error", msg); }
    }
}
