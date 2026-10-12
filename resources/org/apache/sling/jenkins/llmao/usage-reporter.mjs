/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import fs from 'node:fs';

export default function usageReporter(pi) {
    const reportFile = process.env.LLMAO_USAGE_FILE;
    const totals = { input: 0, output: 0, cacheRead: 0, cacheWrite: 0, total: 0 };
    const seenMessages = new WeakSet();
    let model = process.env.LLMAO_MODEL;
    let requests = 0;
    let responsesWithUsage = 0;
    let responsesWithoutUsage = 0;
    let requestInFlight = false;
    let reportedUsage = false;
    let settled = false;
    let aborted = false;
    let writeWarning = false;

    function summary() {
        const complete = settled && !aborted && !requestInFlight && responsesWithoutUsage === 0;
        return {
            schemaVersion: 1,
            provider: 'llmao',
            model,
            state: settled ? 'settled' : 'running',
            aborted,
            usageStatus: responsesWithUsage === 0 ? 'unavailable' : complete ? 'complete' : 'partial',
            requests,
            responsesWithUsage,
            responsesWithoutUsage,
            tokens: responsesWithUsage === 0 ? null : { ...totals }
        };
    }

    function checkpoint() {
        try {
            if (!reportFile) throw new Error('No report file configured');
            // Persist outside the checkout after each response, not only at exit.
            // Rename ensures Jenkins never reads a half-written report on abort.
            fs.writeFileSync(`${reportFile}.tmp`, `${JSON.stringify(summary(), null, 2)}\n`, { mode: 0o600 });
            fs.renameSync(`${reportFile}.tmp`, reportFile);
        } catch {
            if (!writeWarning) {
                process.stderr.write('[Pi usage] Could not persist the token usage report.\n');
                writeWarning = true;
            }
        }
    }

    pi.on('session_start', (_event, ctx) => {
        model = ctx.model?.id ?? model;
        checkpoint();
    });

    pi.on('before_provider_request', () => {
        requests++;
        requestInFlight = true;
        reportedUsage = false;
        settled = false;
        checkpoint();
    });

    pi.on('provider_stream_event', event => {
        // Pi initializes message.usage to zero even when the gateway sends no
        // usage. Observe the raw OpenAI chunk to distinguish missing from zero.
        const usage = event.data?.usage ?? event.data?.choices?.[0]?.usage;
        if (Number.isSafeInteger(usage?.prompt_tokens) && usage.prompt_tokens >= 0 &&
            Number.isSafeInteger(usage?.completion_tokens) && usage.completion_tokens >= 0) {
            reportedUsage = true;
        }
    });

    pi.on('message_end', event => {
        const message = event.message;
        if (message?.role !== 'assistant' || seenMessages.has(message)) return;
        seenMessages.add(message);
        requestInFlight = false;
        const usage = message.usage;
        const tokens = {
            input: usage?.input,
            output: usage?.output,
            cacheRead: usage?.cacheRead ?? 0,
            cacheWrite: usage?.cacheWrite ?? 0,
            total: usage?.totalTokens ?? (usage?.input + usage?.output + (usage?.cacheRead ?? 0) + (usage?.cacheWrite ?? 0))
        };
        if (reportedUsage && Object.values(tokens).every(value => Number.isSafeInteger(value) && value >= 0)) {
            responsesWithUsage++;
            for (const key of Object.keys(totals)) totals[key] += tokens[key];
        } else {
            responsesWithoutUsage++;
        }
        reportedUsage = false;
        checkpoint();
        // Observe only: do not replace the assistant message or its usage.
    });

    pi.on('agent_settled', event => {
        settled = true;
        aborted = Boolean(event.aborted);
        checkpoint();
        const report = summary();
        process.stderr.write(`[Pi usage] ${report.tokens ? `Observed total: ${totals.total} tokens (${report.usageStatus})` : 'Token usage unavailable'}\n`);
    });
}
