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
    function log(value, limit = 6000) {
        let text = String(value ?? '');
        const secret = process.env.LLMAO_PAT;
        if (secret) text = text.split(secret).join('[REDACTED]');
        text = text.replace(/\x1b\[[0-9;]*[a-zA-Z]/g, '');
        if (text.length > limit) text = `[...truncated; last ${limit} characters...]\n${text.slice(-limit)}`;
        process.stderr.write(`[Pi tool] ${text}\n`);
    }

    pi.on('tool_call', event => {
        if (event.toolName === 'bash') log(`bash command:\n${event.input?.command ?? ''}`, 2000);
    });

    pi.on('tool_result', event => {
        if (event.toolName !== 'bash' && !event.isError) return;
        log(`${event.toolName}: ${event.isError ? 'ERROR' : 'OK'}${event.input?.path ? ` (${event.input.path})` : ''}`);
        const output = (event.content ?? []).filter(block => block.type === 'text').map(block => block.text).join('\n');
        if (output) log(output);
        // Return nothing: logging must not change the result seen by the model.
    });
}
