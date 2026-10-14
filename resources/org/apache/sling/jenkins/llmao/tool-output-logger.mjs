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

export default function toolOutputLogger(pi) {
    function log(value, limit = 6000, prefix = 'Pi tool') {
        let text = String(value ?? '');
        const secret = process.env.LLMAO_PAT;
        if (secret) text = text.split(secret).join('[REDACTED]');
        text = text.replace(/\x1b\[[0-9;]*[a-zA-Z]/g, '');
        if (text.length > limit) text = `[...truncated; last ${limit} characters...]\n${text.slice(-limit)}`;
        process.stderr.write(`[${prefix}] ${text}\n`);
    }

    function textBlocks(content) {
        return (Array.isArray(content) ? content : []).filter(block => block?.type === 'text').map(block => block.text).join('\n');
    }

    function progress(value) {
        log(value, 6000, 'Pi');
    }

    pi.on('agent_start', () => { progress('Agent started'); });
    pi.on('before_provider_request', () => { progress('Requesting the next model response'); });

    pi.on('message_end', event => {
        const message = event.message;
        if (message?.role !== 'assistant') return;
        const output = textBlocks(message.content);
        if (output) progress(`Assistant output:\n${output}`);
        progress(`Model response ended: ${message.stopReason ?? 'unknown'}`);
        if (message.errorMessage) progress(message.errorMessage);
        if (message.stopReason === 'length') progress('Model output was truncated. Increase llmaoMaxTokens only if the gateway quota allows it.');
        // Observe only: do not change the assistant message or print reasoning.
    });

    pi.on('agent_settled', event => { progress(event.aborted ? 'Agent aborted' : 'Agent settled'); });

    pi.on('tool_call', event => {
        if (event.toolName === 'bash') log(`bash command:\n${event.input?.command ?? ''}`, 2000);
        else log(`${event.toolName}: starting${event.input?.path ? ` (${event.input.path})` : ''}${event.input?.pattern ? ` pattern=${event.input.pattern}` : ''}`, 2000);
    });

    pi.on('tool_result', event => {
        log(`${event.toolName}: ${event.isError ? 'ERROR' : 'OK'}${event.input?.path ? ` (${event.input.path})` : ''}`);
        // Keep successful file/skill reads private, but show other tool output.
        if (event.toolName !== 'read' || event.isError) {
            const output = textBlocks(event.content);
            if (output) log(output);
        }
        // Return nothing: logging must not change the result seen by the model.
    });
}
