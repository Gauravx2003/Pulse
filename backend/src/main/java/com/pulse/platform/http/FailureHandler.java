package com.pulse.platform.http;

import com.pulse.platform.db.QueryValidationException;
import io.vertx.core.Handler;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.HttpException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * The one place that writes error responses (design.md decision 1): every route failure goes
 * through {@code ctx.fail(status, exception)} to here and comes back as
 * {@code {"error": {"code", "message", "requestId"[, "field"][, "retryAfter"]}}}. Unknown failures
 * become a generic 500 quoting the request id, with no stack trace or SQL in the body; the detail goes
 * to the log only, redacted (task 3.6). Task 3.8 completes the full error contract.
 */
public class FailureHandler implements Handler<RoutingContext>
{
    private static final Logger log = LoggerFactory.getLogger(FailureHandler.class);

    @Override
    public void handle(RoutingContext ctx)
    {
        Throwable failure = ctx.failure();
        String requestId = RequestId.of(ctx);

        int status;
        String code;
        String message;
        JsonObject details = new JsonObject();

        if (failure instanceof ApiException api)
        {
            status = api.status();
            code = api.code();
            message = api.getMessage();
            details = api.details();
        }
        else if (failure instanceof QueryValidationException invalid)
        {
            status = 400;
            code = "invalid_parameter";
            message = invalid.getMessage();
        }
        else if (statusOf(ctx, failure) == 413)
        {
            status = 413;
            code = "payload_too_large";
            message = "The request body is too large.";
        }
        else if (statusOf(ctx, failure) >= 400 && statusOf(ctx, failure) < 500)
        {
            status = statusOf(ctx, failure);
            code = "http_" + status;
            message = "Request failed";
        }
        else
        {
            status = 500;
            code = "internal_error";
            message = "Something went wrong. Quote request id " + requestId + " when reporting it.";

            MDC.put(RequestId.MDC_KEY, requestId);

            try
            {
                log.error("Unhandled failure on {} {} (request {})", ctx.request().method(), ctx.request().path(),
                        requestId, failure);
            }
            finally
            {
                MDC.remove(RequestId.MDC_KEY);
            }
        }

        if (ctx.response().ended())
        {
            return;
        }

        JsonObject error = new JsonObject()
                .put("code", code)
                .put("message", message)
                .put("requestId", requestId)
                .mergeIn(details);

        if (error.getValue("retryAfter") != null)
        {
            ctx.response().putHeader("Retry-After", String.valueOf(error.getValue("retryAfter")));
        }

        ctx.response()
                .setStatusCode(status)
                .putHeader("Content-Type", "application/json")
                .putHeader(RequestId.HEADER, requestId)
                .end(new JsonObject().put("error", error).encode());
    }

    /**
     * The status a framework handler failed with (BodyHandler 413, CORS 403, routing 404/405); a plain
     * exception passed to {@code ctx.fail(t)} reports 500.
     */
    private static int statusOf(RoutingContext ctx, Throwable failure)
    {
        if (failure instanceof HttpException http)
        {
            return http.getStatusCode();
        }

        return ctx.statusCode();
    }
}
