package com.pulse.platform.outbox;

import com.pulse.platform.db.BaseRepository;
import com.pulse.platform.db.SqlExecutor;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Tuple;

/**
 * Queues outbound messages (invite and reset links, Slack copies) in the {@code outbox} table. A
 * delivery job sends them when mail or chat is configured; until then they stay in the table.
 * Pass the executor of the caller's transaction so the message commits with the action.
 */
public class OutboxRepository extends BaseRepository
{
    public OutboxRepository(SqlExecutor sql)
    {
        super(sql);
    }

    /**
     * @param channel   {@code email}, {@code slack} or {@code teams}
     * @param recipient address or channel
     * @param payload   machine-readable details; never passwords or secrets
     */
    public Future<Void> enqueue(String channel, String recipient, String subject, String body, JsonObject payload)
    {
        return execute("INSERT INTO outbox (channel, recipient, subject, body, payload) VALUES ($1, $2, $3, $4, $5)",
                Tuple.of(channel, recipient, subject, body, payload == null ? new JsonObject() : payload))
                .mapEmpty();
    }
}
