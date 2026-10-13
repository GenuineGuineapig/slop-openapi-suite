package dev.openapifn.spring.app;

import java.util.ArrayList;
import java.util.List;

/** Records what the controller received, for assertions. */
public final class Calls {

    public static final List<Object> RECEIVED = new ArrayList<>();

    private Calls() {
    }
}
