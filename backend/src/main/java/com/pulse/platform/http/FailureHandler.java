package com.pulse.platform.http;

import com.pulse.platform.db.QueryValidationException;
import io.vertx.core.Handler;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.HttpException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.util.Map;

/**
 * The one place that writes error responses (design.md decision 1, spec api-conventions "Error
 * contract", task 3.8): every route failure goes through {@code ctx.fail(status, exception)} to here,
 * and unmatched routes reach it through the router's error handlers. The body is always
 * {@code {"error": {"code", "message", "requestId"[, "field"][, "retryAfter"][, "currentVersion"]}}}:
 *
 * <ul>
 *   <li>400 validation: {@code invalid_field} (with {@code field}), {@code invalid_parameter}, {@code invalid_body}</li>
 *   <li>401 {@code unauthenticated}; 403 {@code no_access}, {@code csrf_invalid}, {@code origin_rejected}</li>
 *   <li>404 {@code not_found}; 405 {@code method_not_allowed}; 409 {@code version_conflict} (with
 *       {@code currentVersion}); 413 {@code payload_too_large}</li>
 *   <li>422 a code naming the business rule, e.g. {@code self_action_refused}; 429 {@code rate_limited}
 *       (with {@code retryAfter} and a Retry-After header)</li>
 *   <li>500 {@code internal_error}: generic, quoting the request id, with no stack trace or SQL in the
 *       body; the detail goes to the log only, redacted (task 3.6)</li>
 * </ul>
 *
 * A failure without an {@link ApiException} gets the stable code of its status from {@link #CODES}.
 */
public class FailureHandler implements Handler<RoutingContext>
{
    private static final Logger log = LoggerFactory.getLogger(FailureHandler.class);

    /** Stable code and message for each status a framework handler or the router fails with. */
    static final Map<Integer, String[]> CODES = Map.of(
            400, new String[] {"bad_request", "The request is not valid."},
            401, new String[] {"unauthenticated", "Sign in to continue."},
            403, new String[] {"no_access", "You don't have access"},
            404, new String[] {"not_found", "Not found"},
            405, new String[] {"method_not_allowed", "This method is not allowed here."},
            409, new String[] {"conflict", "The request conflicts with the current state."},
            413, new String[] {"payload_too_large", "The request body is too large."},
            415, new String[] {"unsupported_media_type", "Send the body as JSON."},
            422, new String[] {"unprocessable", "The request breaks a business rule."},
            429, new String[] {"rate_limited", "Too many requests. Try again in a moment."});

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
            details = new JsonObject().put("field", invalid.field());
        }
        else if (statusOf(ctx, failure) >= 400 && statusOf(ctx, failure) < 500)
        {
            status = statusOf(ctx, failure);

            String[] known = CODES.getOrDefault(status, new String[] {"http_" + status, "Request failed"});

            code = known[0];
            message = known[1];
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
