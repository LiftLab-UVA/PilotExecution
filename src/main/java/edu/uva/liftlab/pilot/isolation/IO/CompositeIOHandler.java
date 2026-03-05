package edu.uva.liftlab.pilot.isolation.IO;

import java.util.Arrays;
import java.util.List;

class CompositeIOHandler implements IOOperationHandler {
    private final List<IOOperationHandler> handlers;

    public CompositeIOHandler() {
        this.handlers = Arrays.asList(
                new FileHandler(),
                new PathHandler(),
                new FileChannelHandler()
        );
    }

    @Override
    public boolean handle(IOContext context) {
        for (IOOperationHandler handler : handlers) {
            handler.handle(context);
        }
        return false;
    }
}
