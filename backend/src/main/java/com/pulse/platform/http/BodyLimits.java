package com.pulse.platform.http;

import io.vertx.core.Handler;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;

/**
 * Reads request bodies with a size limit enforced while the body streams in (task 3.6, spec
 * security-baseline "Rate limiting and payload limits"): Vert.x's {@link BodyHandler} rejects a
 * declared {@code Content-Length} over the limit before reading and stops reading as soon as the
 * received bytes pass it, answering 413. CSV and multipart uploads get the upload limit (5 MB); every
 * other body the smaller default.
 */
public final class BodyLimits implements Handler<RoutingContext>
{
    private final BodyHandler regular;

    private final BodyHandler upload;

    public BodyLimits(SecuritySettings settings)
    {
        this.regular = BodyHandler.create(false).setBodyLimit(settings.bodyLimitBytes());
        this.upload = BodyHandler.create(System.getProperty("java.io.tmpdir") + "/pulse-uploads")
                .setBodyLimit(settings.uploadLimitBytes())
                .setDeleteUploadedFilesOnEnd(true);
    }

    @Override
    public void handle(RoutingContext ctx)
    {
        String type = ctx.request().getHeader("Content-Type");

        boolean isUpload = type != null && (type.toLowerCase().startsWith("multipart/form-data")
                || type.toLowerCase().startsWith("text/csv"));

        (isUpload ? upload : regular).handle(ctx);
    }
}
