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

// Consume the JSONL protocol continuously. Progress belongs on stderr; only
// the final SUCCESS/FAILURE decision belongs on stdout for run-docker.sh.
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
function textBlocks(content) {
    if (typeof content === 'string') return content;
    return (Array.isArray(content) ? content : []).filter(block => block?.type === 'text').map(block => block.text).join('\n');
}

let lastAssistant;
let settled = false;
let aborted = false;
let protocolError = false;
let quotaReported = false;
const tools = new Map();

function diagnostic(text) {
    if (!quotaReported && /\b429\b|rate limit exceeded/i.test(text)) {
        quotaReported = true;
        log('LLMAO quota/rate limit rejected the request. Wait for the reported reset or request a higher limit; retries are disabled.');
    }
    log(text);
}

function event(record) {
    switch (record.type) {
        case 'agent_start':
            settled = false;
            log('Agent started');
            break;
        case 'turn_start':
            log('Requesting the next model response');
            break;
        case 'tool_execution_start': {
            tools.set(record.toolCallId, record.toolName);
            const detail = record.toolName === 'bash' ? record.args?.command : record.args?.path ?? record.args?.pattern ?? '';
            log(`Tool ${record.toolName}: ${detail}`, 2000);
            break;
        }
        case 'tool_execution_end': {
            const name = record.toolName ?? tools.get(record.toolCallId) ?? 'unknown';
            log(`Tool ${name}: ${record.isError ? 'ERROR' : 'OK'}${record.durationMs === undefined ? '' : ` (${record.durationMs} ms)`}`);
            // Do not dump source/skills/prompts. Shell output and tool errors
            // carry the build diagnostics; keep their tail bounded and redacted.
            if (name === 'bash' || record.isError) {
                const output = textBlocks(record.result?.content);
                if (output) diagnostic(output);
            }
            tools.delete(record.toolCallId);
            break;
        }
        case 'message_end': {
            const message = record.message;
            if (message?.role !== 'assistant') break;
            lastAssistant = message;
            if (message.usage) {
                const usage = message.usage;
                log(`Tokens: input=${usage.input ?? 0}, output=${usage.output ?? 0}, cacheRead=${usage.cacheRead ?? 0}, cacheWrite=${usage.cacheWrite ?? 0}`);
            }
            log(`Model response ended: ${message.stopReason ?? 'unknown'}`);
            if (message.errorMessage) diagnostic(message.errorMessage);
            if (message.stopReason === 'length') log('Model output was truncated. Increase llmaoMaxTokens only if the gateway quota allows it.');
            break;
        }
        case 'agent_settled':
            settled = true;
            aborted = Boolean(record.aborted);
            log(aborted ? 'Agent aborted' : 'Agent settled');
            break;
    }
}

// Split only on LF, not Unicode line separators (which can occur in JSON).
function lines(stream, consume) {
    let pending = '';
    stream.setEncoding('utf8');
    stream.on('data', chunk => {
        pending += chunk;
        let newline;
        while ((newline = pending.indexOf('\n')) !== -1) {
            const line = pending.slice(0, newline).replace(/\r$/, '');
            pending = pending.slice(newline + 1);
            if (line) consume(line);
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
lines(child.stderr, diagnostic);
child.on('error', error => {
    diagnostic(`Could not start Pi: ${error.message}`);
    protocolError = true;
});
child.on('close', (code, signal) => {
    if (signal) log(`Pi terminated by ${signal}`);
    const answer = textBlocks(lastAssistant?.content).trim();
    const hasToolCalls = Array.isArray(lastAssistant?.content) && lastAssistant.content.some(block => block?.type === 'toolCall');
    const complete = code === 0 && !signal && !protocolError && settled && !aborted &&
        lastAssistant?.stopReason === 'stop' && !hasToolCalls;
    const success = complete && answer === 'SUCCESS';
    if (complete && answer !== 'SUCCESS' && answer !== 'FAILURE') log('Final model response was not exactly SUCCESS or FAILURE.');
    if (!complete) log('Pi did not complete a successful, settled model response.');
    process.stdout.write(`${success ? 'SUCCESS' : 'FAILURE'}\n`);
    process.exitCode = success ? 0 : (code && code > 0 ? code : 1);
});
