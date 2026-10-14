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

import { spawn } from 'node:child_process';
import fs from 'node:fs';

// Consume JSONL lifecycle events without interpreting assistant prose.
// The bundled output logger handles progress and tool/assistant diagnostics.
const secret = process.env.LLMAO_PAT;
function sanitize(value, limit = 6000) {
    let text = String(value ?? '');
    if (secret) text = text.split(secret).join('[REDACTED]');
    text = text.replace(/\x1b\[[0-9;]*[a-zA-Z]/g, '');
    return text.length > limit ? `[...truncated; last ${limit} characters...]\n${text.slice(-limit)}` : text;
}
function log(value, limit) {
    process.stderr.write(`[Pi] ${sanitize(value, limit)}\n`);
}
let lastAssistant;
let settled = false;
let aborted = false;
let protocolError = false;
let quotaReported = false;

function diagnostic(text) {
    if (!quotaReported && /\b429\b|rate limit exceeded/i.test(text)) {
        quotaReported = true;
        log('LLMAO quota/rate limit rejected the request. Pi retry settings and the job timeout still apply.');
    }
    log(text);
}

function event(record) {
    switch (record.type) {
        case 'agent_start':
            settled = false;
            aborted = false;
            lastAssistant = undefined;
            break;
        case 'message_end': {
            const message = record.message;
            if (message?.role !== 'assistant') break;
            lastAssistant = message;
            if (message.errorMessage) diagnostic(message.errorMessage);
            break;
        }
        case 'agent_settled':
            settled = true;
            aborted = Boolean(record.aborted);
            break;
    }
}

// Split only on LF, not Unicode line separators (which can occur in JSON).
function lines(stream, consume, keepEmpty = false) {
    let pending = '';
    stream.setEncoding('utf8');
    stream.on('data', chunk => {
        pending += chunk;
        let newline;
        while ((newline = pending.indexOf('\n')) !== -1) {
            const line = pending.slice(0, newline).replace(/\r$/, '');
            pending = pending.slice(newline + 1);
            if (line || keepEmpty) consume(line);
        }
    });
    stream.on('end', () => { if (pending) consume(pending); });
}

try {
    const config = JSON.parse(fs.readFileSync(`${process.env.PI_CODING_AGENT_DIR}/models.json`, 'utf8'));
    const model = config.providers.llmao.models.find(item => item.id === process.env.LLMAO_MODEL);
    log(`Model llmao/${process.env.LLMAO_MODEL}, max output tokens=${model?.maxTokens ?? 'provider default'}`);
} catch {
    log('Could not read model budget; Pi will report configuration errors.');
}

const child = spawn('pi', process.argv.slice(2), { stdio: ['inherit', 'pipe', 'pipe'] });
lines(child.stdout, line => {
    try {
        event(JSON.parse(line));
    } catch {
        protocolError = true;
        // Never print an invalid protocol record: it could contain a prompt.
        log('Invalid Pi JSON event; the run will fail.');
    }
});
lines(child.stderr, text => process.stderr.write(`${sanitize(text)}\n`), true);
child.on('error', error => {
    diagnostic(`Could not start Pi: ${error.message}`);
    protocolError = true;
});
child.on('close', (code, signal) => {
    if (signal) log(`Pi terminated by ${signal}`);
    const hasToolCalls = Array.isArray(lastAssistant?.content) && lastAssistant.content.some(block => block?.type === 'toolCall');
    const complete = code === 0 && !signal && !protocolError && settled && !aborted &&
        lastAssistant?.stopReason === 'stop' && !lastAssistant?.errorMessage && !hasToolCalls;
    if (!complete) log('Pi did not complete a successful, settled model response.');
    process.exitCode = complete ? 0 : (code && code > 0 ? code : 1);
});
