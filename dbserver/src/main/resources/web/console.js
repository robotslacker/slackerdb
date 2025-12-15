        // WebSocket and state
        let ws = null;
        let sessionId = null;
        let currentTaskId = null;
        let commandHistory = [];
        let historyIndex = -1;
        let tempInput = '';
        let heartbeatInterval = null;
        let currentDataServiceSchema = null;

        // DOM elements
        const outputEl = document.getElementById('output');
        const commandInput = document.getElementById('commandInput');
        const statusIndicator = document.getElementById('statusIndicator');
        const statusText = document.getElementById('statusText');
        const databaseNameEl = document.getElementById('databaseName');

        // Helper to append a line to output
        function output(line, className = '') {
            const div = document.createElement('div');
            if (className) div.className = className;
            div.textContent = line;
            outputEl.appendChild(div);
            outputEl.scrollTop = outputEl.scrollHeight;
        }

        // Show a toast notification
        function showToast(message, isError = false) {
            // Remove existing toast if any
            const existing = document.querySelector('.toast');
            if (existing) existing.remove();

            const toast = document.createElement('div');
            toast.className = 'toast' + (isError ? ' error' : '');
            toast.textContent = message;
            document.body.appendChild(toast);

            // Trigger animation
            setTimeout(() => toast.classList.add('show'), 10);

            // Auto remove after 3 seconds
            setTimeout(() => {
                toast.classList.remove('show');
                setTimeout(() => {
                    if (toast.parentNode) toast.parentNode.removeChild(toast);
                }, 300);
            }, 3000);
        }

        // Helper to save a blob with file picker dialog (or fallback to anchor download)
        async function saveFileWithPicker(blob, defaultName) {
            // Use the File System Access API if available (modern browsers)
            if ('showSaveFilePicker' in window) {
                try {
                    const options = {
                        suggestedName: defaultName,
                        types: [
                            {
                                description: 'JSON Files',
                                accept: { 'application/json': ['.json'] },
                            },
                        ],
                    };
                    const fileHandle = await window.showSaveFilePicker(options);
                    const writable = await fileHandle.createWritable();
                    await writable.write(blob);
                    await writable.close();
                    return true;
                } catch (err) {
                    // User cancelled the picker or an error occurred
                    if (err.name !== 'AbortError') {
                        console.error('File picker error:', err);
                    }
                    // Fallback to anchor download
                }
            }
            // Fallback: create a download link and trigger click
            const url = window.URL.createObjectURL(blob);
            const a = document.createElement('a');
            a.href = url;
            a.download = defaultName;
            document.body.appendChild(a);
            a.click();
            window.URL.revokeObjectURL(url);
            document.body.removeChild(a);
            return false;
        }

        // Helper to append a prompt + command
        function outputCommand(cmd) {
            const div = document.createElement('div');
            div.innerHTML = `<span class="prompt">slacker></span> <span class="command">${escapeHtml(cmd)}</span>`;
            outputEl.appendChild(div);
            outputEl.scrollTop = outputEl.scrollHeight;
        }

        // Helper to append a table
        function outputTable(columns, rows) {
            const table = document.createElement('table');
            const thead = document.createElement('thead');
            const tbody = document.createElement('tbody');
            const headerRow = document.createElement('tr');
            columns.forEach(col => {
                const th = document.createElement('th');
                th.textContent = col;
                headerRow.appendChild(th);
            });
            thead.appendChild(headerRow);
            rows.forEach(row => {
                const tr = document.createElement('tr');
                columns.forEach(col => {
                    const td = document.createElement('td');
                    td.textContent = row[col] !== null && row[col] !== undefined ? String(row[col]) : 'NULL';
                    tr.appendChild(td);
                });
                tbody.appendChild(tr);
            });
            table.appendChild(thead);
            table.appendChild(tbody);
            outputEl.appendChild(table);
            outputEl.scrollTop = outputEl.scrollHeight;
        }

        function escapeHtml(text) {
            const div = document.createElement('div');
            div.textContent = text;
            return div.innerHTML;
        }

        // Update connection status
        function updateStatus(connected) {
            statusIndicator.className = 'status-indicator ' + (connected ? 'connected' : 'disconnected');
            statusText.textContent = connected ? 'Connected' : 'Disconnected';
        }

        // Connect WebSocket
        function connectWebSocket() {
            const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
            const wsUrl = `${protocol}//${window.location.host}/sql/ws`;
            ws = new WebSocket(wsUrl);

            ws.onopen = () => {
                updateStatus(true);
                output('Connected to server.', 'success');
                startSession();
                // Start heartbeat to keep connection alive
                if (heartbeatInterval) clearInterval(heartbeatInterval);
                heartbeatInterval = setInterval(() => {
                    if (ws && ws.readyState === WebSocket.OPEN) {
                        ws.send("ping");
                    }
                }, 20000);
                // Fetch database name from status endpoint
                fetch('/status')
                    .then(response => response.json())
                    .then(data => {
                        console.log('Status response:', data);
                        let dbName = 'unknown';
                        const params = data.parameters;
                        if (params) {
                            if (params.data && params.data !== '') {
                                dbName = params.data;
                            } else if (params.dataDir && params.dataDir === ':memory:') {
                                dbName = 'memory';
                            } else if (params.dataDir && params.dataDir !== '') {
                                // Extract the last part of the path as database name
                                const parts = params.dataDir.split(/[/\\]/);
                                dbName = parts[parts.length - 1] || 'file';
                            }
                        }
                        databaseNameEl.textContent = `Database: ${dbName}`;
                        // Update Save button state based on data_service_schema parameter
                        if (params) {
                            currentDataServiceSchema = params.dataServiceSchema;
                            updateSaveButtonState(currentDataServiceSchema);
                        }
                    })
                    .catch(err => {
                        console.error('Failed to fetch status:', err);
                        databaseNameEl.textContent = 'Database: error';
                    });
            };

            ws.onclose = () => {
                updateStatus(false);
                output('Disconnected from server.', 'error');
                // Clear heartbeat interval
                if (heartbeatInterval) {
                    clearInterval(heartbeatInterval);
                    heartbeatInterval = null;
                }
                setTimeout(connectWebSocket, 3000);
            };

            ws.onerror = (err) => {
                // Extract error message from event
                let errMsg = '';
                if (err instanceof Error) {
                    errMsg = err.message;
                } else if (err.type) {
                    errMsg = `type: ${err.type}`;
                } else if (err.target && err.target.readyState === WebSocket.CLOSED) {
                    errMsg = 'WebSocket closed';
                } else {
                    errMsg = String(err);
                    // If it's the generic [object Event], try to get more info
                    if (errMsg === '[object Event]') {
                        errMsg = 'WebSocket error event';
                    }
                }
                // Filter out connection idle timeout errors to avoid disturbing the user
                const lower = errMsg.toLowerCase();
                if (!lower.includes('connection idle timeout') && !lower.includes('websockettimeoutexception')) {
                    // Output a user-friendly error message
                    output('WebSocket error: ' + errMsg, 'error');
                }
            };

            ws.onmessage = (event) => {
                const data = event.data;
                // Handle heartbeat "pong" response
                if (data === 'pong' || data === '"pong"') {
                    // Silently ignore, no need to log
                    return;
                }
                try {
                    const msg = JSON.parse(data);
                    handleMessage(msg);
                } catch (e) {
                    output('Invalid message: ' + e, 'error');
                }
            };
        }

        // Start a new session
        function startSession() {
            sendMessage({
                id: generateId(),
                type: 'start',
                data: {}
            });
        }

        // Send a message to WebSocket
        function sendMessage(msg) {
            if (ws && ws.readyState === WebSocket.OPEN) {
                ws.send(JSON.stringify(msg));
            } else {
                output('WebSocket not ready', 'error');
            }
        }

        function generateId() {
            return Date.now() + '-' + Math.random().toString(36).substr(2, 9);
        }

        // Handle incoming messages
        function handleMessage(msg) {
            const { id, type, data, error } = msg;
            if (error) {
                output('Error: ' + error, 'error');
                return;
            }
            switch (type) {
                case 'start':
                    sessionId = data.sessionId;
                    output('Session started. Type SQL to execute.', 'success');
                    break;
                case 'exec':
                    currentTaskId = data.taskId;
                    output('Query executing...', 'message');
                    // Poll for completion
                    setTimeout(() => fetchResult(), 100);
                    break;
                case 'fetch':
                    if (data.status === 'running') {
                        setTimeout(() => fetchResult(), 200);
                    } else if (data.status === 'error') {
                        output('Query failed: ' + data.error, 'error');
                        resetTask();
                    } else if (data.status === 'completed') {
                        if (data.updateCount !== undefined) {
                            output(`Query completed. ${data.updateCount} row(s) affected.`, 'success');
                            resetTask();
                        } else {
                            // Result set
                            const columns = data.columns || [];
                            const rows = data.rows || [];
                            if (columns.length > 0) {
                                outputTable(columns, rows);
                                output(`Fetched ${rows.length} rows.`, 'message');
                            } else {
                                output('No rows returned.', 'message');
                            }
                            if (data.hasMore) {
                                output('More rows available. Fetching...', 'message');
                                setTimeout(() => fetchResult(), 0);
                            } else {
                                resetTask();
                            }
                        }
                    }
                    break;
                case 'cancel':
                    output('Query canceled.', 'message');
                    resetTask();
                    break;
                case 'close':
                    output('Session closed.', 'message');
                    sessionId = null;
                    break;
                default:
                    output('Unknown message type: ' + type, 'error');
            }
        }

        // Execute SQL command
        function executeCommand(sql) {
            if (!sql.trim()) return;
            if (!sessionId) {
                output('No active session.', 'error');
                return;
            }
            outputCommand(sql);
            commandHistory.push(sql);
            historyIndex = commandHistory.length;
            sendMessage({
                id: generateId(),
                type: 'exec',
                data: {
                    sessionId: sessionId,
                    sql: sql,
                    fetchSize: 100
                }
            });
        }

        // Fetch result for current task
        function fetchResult() {
            if (!sessionId || !currentTaskId) return;
            sendMessage({
                id: generateId(),
                type: 'fetch',
                data: {
                    sessionId: sessionId,
                    taskId: currentTaskId,
                    maxRows: 100
                }
            });
        }

        // Reset task state
        function resetTask() {
            currentTaskId = null;
        }

        // Cancel current query
        function cancelQuery() {
            if (!sessionId) return;
            sendMessage({
                id: generateId(),
                type: 'cancel',
                data: { sessionId: sessionId }
            });
        }

        // Input handling
        commandInput.addEventListener('keydown', (e) => {
            if (e.key === 'Enter') {
                if (e.shiftKey) {
                    // Insert new line (not supported in input, but we can switch to textarea)
                    // For simplicity, we'll just ignore and keep as single line.
                } else {
                    e.preventDefault();
                    const cmd = commandInput.value.trim();
                    if (cmd) {
                        executeCommand(cmd);
                        commandInput.value = '';
                    }
                }
            } else if (e.key === 'ArrowUp') {
                e.preventDefault();
                if (commandHistory.length > 0) {
                    if (historyIndex === -1) {
                        tempInput = commandInput.value;
                        historyIndex = commandHistory.length;
                    }
                    historyIndex = Math.max(0, historyIndex - 1);
                    commandInput.value = commandHistory[historyIndex];
                }
            } else if (e.key === 'ArrowDown') {
                e.preventDefault();
                if (commandHistory.length > 0) {
                    if (historyIndex < commandHistory.length - 1) {
                        historyIndex++;
                        commandInput.value = commandHistory[historyIndex];
                    } else {
                        historyIndex = commandHistory.length;
                        commandInput.value = tempInput;
                    }
                }
            }
        });

        // Focus input on click anywhere except output area, modals, and sidebar
        document.addEventListener('click', (event) => {
            const outputEl = document.getElementById('output');
            const target = event.target;
            // Check if target is inside a modal overlay or sidebar
            const isInModal = target.closest('.status-modal-overlay, .log-modal-overlay, .backup-modal-overlay');
            const isInSidebar = target.closest('#sidebar');
            if (!outputEl.contains(target) && !isInModal && !isInSidebar) {
                commandInput.focus();
            }
        });

        // Sidebar functionality
        const sidebar = document.getElementById('sidebar');
        const sidebarToggle = document.getElementById('sidebarToggle');
        const sidebarButtons = document.querySelectorAll('.sidebar-btn');
        const panels = document.querySelectorAll('.sidebar-panel');

        // Toggle sidebar collapse/expand
        sidebarToggle.addEventListener('click', () => {
            sidebar.classList.toggle('collapsed');
            if (sidebar.classList.contains('collapsed')) {
                sidebarToggle.textContent = 'Expand';
            } else {
                sidebarToggle.textContent = 'Collapse';
            }
        });

        // Expand sidebar via collapse icon
        document.getElementById('sidebarCollapseIcon').addEventListener('click', () => {
            sidebar.classList.remove('collapsed');
            sidebarToggle.textContent = 'Collapse';
        });

        // Switch panels
        sidebarButtons.forEach(btn => {
            btn.addEventListener('click', () => {
                const panelId = btn.getAttribute('data-panel');
                // Update active button
                sidebarButtons.forEach(b => b.classList.remove('active'));
                btn.classList.add('active');
                // Show corresponding panel
                panels.forEach(p => p.classList.remove('active'));
                document.getElementById(`panel-${panelId}`).classList.add('active');
            });
        });

        // Left sidebar functionality
        const leftSidebar = document.getElementById('leftSidebar');
        const leftSidebarToggle = document.getElementById('leftSidebarToggle');
        const leftSidebarButtons = document.querySelectorAll('#leftSidebar .sidebar-btn');
        const leftPanels = document.querySelectorAll('#leftSidebar .sidebar-panel');

        // Toggle left sidebar collapse/expand
        leftSidebarToggle.addEventListener('click', () => {
            leftSidebar.classList.toggle('collapsed');
            if (leftSidebar.classList.contains('collapsed')) {
                leftSidebarToggle.textContent = 'Expand';
            } else {
                leftSidebarToggle.textContent = 'Collapse';
            }
        });

        // Expand left sidebar via collapse icon
        document.getElementById('leftSidebarCollapseIcon').addEventListener('click', () => {
            leftSidebar.classList.remove('collapsed');
            leftSidebarToggle.textContent = 'Collapse';
        });

        // Switch left sidebar panels and main view
        leftSidebarButtons.forEach(btn => {
            btn.addEventListener('click', () => {
                const panelId = btn.getAttribute('data-panel');
                // Update active button
                leftSidebarButtons.forEach(b => b.classList.remove('active'));
                btn.classList.add('active');
                // Show corresponding panel
                leftPanels.forEach(p => p.classList.remove('active'));
                document.getElementById(`panel-${panelId}`).classList.add('active');
                // Switch main view
                switchMainView(panelId);
            });
        });

        // Function to switch main view between Console, Data Service, MCP Tool, and MCP Resource
        function switchMainView(panelId) {
            const outputEl = document.getElementById('output');
            const inputLine = document.querySelector('.input-line');
            const help = document.querySelector('.help');
            const dataServiceMain = document.getElementById('data-service-main');
            const mcpToolMain = document.getElementById('mcp-tool-main');
            const mcpResourceMain = document.getElementById('mcp-resource-main');
            // Hide all main views first
            outputEl.style.display = 'none';
            inputLine.style.display = 'none';
            help.style.display = 'none';
            dataServiceMain.style.display = 'none';
            mcpToolMain.style.display = 'none';
            mcpResourceMain.style.display = 'none';
            if (panelId === 'console') {
                // Show SQL console elements
                outputEl.style.display = 'block';
                inputLine.style.display = 'flex';
                help.style.display = 'block';
                // Focus input
                document.getElementById('commandInput').focus();
            } else if (panelId === 'data-service') {
                // Show Data Service elements
                dataServiceMain.style.display = 'block';
                // Load service list if not already loaded
                loadServiceList();
                // Update Save button state based on stored schema
                updateSaveButtonState(currentDataServiceSchema);
            } else if (panelId === 'mcp-tool') {
                // Show MCP Tool elements
                mcpToolMain.style.display = 'block';
                // Load MCP Tool list if not already loaded
                loadMCPToolList();
            } else if (panelId === 'mcp-resource') {
                // Show MCP Resource elements
                mcpResourceMain.style.display = 'block';
                // Load MCP Resource list if not already loaded
                loadMCPResourceList();
            }
        }

        // Update status panel with current connection info
        function updateStatusPanel() {
            const panelStatusText = document.getElementById('panelStatusText');
            const panelDbName = document.getElementById('panelDbName');
            panelStatusText.textContent = statusText.textContent;
            panelDbName.textContent = databaseNameEl.textContent.replace('Database: ', '');
            // TODO: fetch active sessions via API
        }

        // Backup button
        document.getElementById('backupBtn').addEventListener('click', () => {
            const path = document.getElementById('backupPath').value.trim();
            if (!path) {
                document.getElementById('backupResult').innerHTML = '<span style="color:#ff6b6b">Please enter backup path</span>';
                return;
            }
            document.getElementById('backupResult').innerHTML = '<span style="color:#aaa">Backup in progress...</span>';
            fetch('/api/backup', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ path: path })
            })
            .then(response => response.json())
            .then(data => {
                if (data.success) {
                    document.getElementById('backupResult').innerHTML = '<span style="color:#66bb6a">Backup successful</span>';
                } else {
                    document.getElementById('backupResult').innerHTML = `<span style="color:#ff6b6b">Failed: ${data.error}</span>`;
                }
            })
            .catch(err => {
                document.getElementById('backupResult').innerHTML = `<span style="color:#ff6b6b">Request error: ${err}</span>`;
            });
        });


        // Logs refresh
        function refreshLogs() {
            const logContent = document.getElementById('logContent');
            logContent.textContent = 'Loading...';
            fetch('/logfile')
            .then(response => response.text())
            .then(text => {
                logContent.textContent = text;
            })
            .catch(err => {
                logContent.textContent = 'Failed to load logs: ' + err;
            });
        }
        document.getElementById('refreshLogs').addEventListener('click', refreshLogs);
        // Load logs initially
        refreshLogs();

        // Update status panel periodically
        setInterval(updateStatusPanel, 5000);
        updateStatusPanel();

        // Initialize
        connectWebSocket();

        // Status Modal functionality
        const statusModalOverlay = document.createElement('div');
        statusModalOverlay.className = 'status-modal-overlay';
        statusModalOverlay.innerHTML = `
            <div class="status-modal">
                <div class="status-modal-header">
                    <h2>Server Status Details</h2>
                    <button class="status-modal-close" title="Close">×</button>
                </div>
                <div class="status-modal-content" id="statusModalContent">
                    <div class="status-section">
                        <div class="status-section-header">
                            <h3>Server</h3>
                            <span class="toggle-icon">▼</span>
                        </div>
                        <div class="status-section-content" id="server-section"></div>
                    </div>
                    <div class="status-section">
                        <div class="status-section-header">
                            <h3>Database</h3>
                            <span class="toggle-icon">▼</span>
                        </div>
                        <div class="status-section-content" id="database-section"></div>
                    </div>
                    <div class="status-section">
                        <div class="status-section-header">
                            <h3>Parameters</h3>
                            <span class="toggle-icon">▼</span>
                        </div>
                        <div class="status-section-content" id="parameters-section"></div>
                    </div>
                    <div class="status-section">
                        <div class="status-section-header">
                            <h3>Usage</h3>
                            <span class="toggle-icon">▼</span>
                        </div>
                        <div class="status-section-content" id="usage-section"></div>
                    </div>
                    <div class="status-section">
                        <div class="status-section-header">
                            <h3>Extensions</h3>
                            <span class="toggle-icon">▼</span>
                        </div>
                        <div class="status-section-content" id="extensions-section"></div>
                    </div>
                    <div class="status-section">
                        <div class="status-section-header">
                            <h3>Sessions</h3>
                            <span class="toggle-icon">▼</span>
                        </div>
                        <div class="status-section-content" id="sessions-section"></div>
                    </div>
                </div>
            </div>
        `;
        document.body.appendChild(statusModalOverlay);

        const modalCloseBtn = statusModalOverlay.querySelector('.status-modal-close');
        const sectionHeaders = statusModalOverlay.querySelectorAll('.status-section-header');

        // Toggle section collapse/expand
        sectionHeaders.forEach(header => {
            header.addEventListener('click', () => {
                header.classList.toggle('collapsed');
                const content = header.nextElementSibling;
                if (header.classList.contains('collapsed')) {
                    content.style.display = 'none';
                } else {
                    content.style.display = 'grid';
                }
            });
        });

        // Close modal
        modalCloseBtn.addEventListener('click', () => {
            statusModalOverlay.classList.remove('active');
        });
        statusModalOverlay.addEventListener('click', (e) => {
            if (e.target === statusModalOverlay) {
                statusModalOverlay.classList.remove('active');
            }
        });

        // Function to open status modal and fetch data
        function openStatusModal() {
            statusModalOverlay.classList.add('active');
            fetch('/status')
                .then(response => response.json())
                .then(data => {
                    renderStatusData(data);
                })
                .catch(err => {
                    console.error('Failed to fetch status:', err);
                    output('Failed to load status details.', 'error');
                });
        }

        // Render status data into sections
        function renderStatusData(data) {
            renderSection('server', data.server);
            renderSection('database', data.database);
            renderSection('parameters', data.parameters);
            renderSection('usage', data.usage);
            renderListSection('extensions', data.extensions);
            renderSessionsSection('sessions', data.sessions);
        }

        function renderSection(sectionId, obj) {
            const container = document.getElementById(`${sectionId}-section`);
            if (!container) return;
            if (!obj || Object.keys(obj).length === 0) {
                container.innerHTML = '<div class="status-item"><span class="status-item-label">No data</span></div>';
                return;
            }
            let html = '';
            for (const [key, value] of Object.entries(obj)) {
                html += `
                    <div class="status-item">
                        <span class="status-item-label">${escapeHtml(key)}</span>
                        <span class="status-item-value">${escapeHtml(String(value))}</span>
                    </div>
                `;
            }
            container.innerHTML = html;
        }

        function renderListSection(sectionId, array) {
            const container = document.getElementById(`${sectionId}-section`);
            if (!container) return;
            if (!array || array.length === 0) {
                container.innerHTML = '<div class="status-item"><span class="status-item-label">None</span></div>';
                return;
            }
            let html = '';
            array.forEach(item => {
                html += `
                    <div class="status-item">
                        <span class="status-item-value">${escapeHtml(item)}</span>
                    </div>
                `;
            });
            container.innerHTML = html;
        }

        function renderSessionsSection(sectionId, sessions) {
            const container = document.getElementById(`${sectionId}-section`);
            if (!container) return;
            if (!sessions || sessions.length === 0) {
                container.innerHTML = '<div class="status-item"><span class="status-item-label">No active sessions</span></div>';
                return;
            }
            // Create a table
            let html = '<table class="status-table"><thead><tr><th>ID</th><th>Client IP</th><th>Connected Time</th><th>Status</th><th>Executing SQL</th></tr></thead><tbody>';
            sessions.forEach(session => {
                const statusClass = session.status === 'RUNNING' ? 'running' : (session.status === 'IDLE' ? 'idle' : 'error');
                html += `
                    <tr>
                        <td>${escapeHtml(session.id)}</td>
                        <td>${escapeHtml(session.clientIp || '')}</td>
                        <td>${escapeHtml(session.connectedTime || '')}</td>
                        <td><span class="status-badge ${statusClass}">${escapeHtml(session.status)}</span></td>
                        <td>${escapeHtml(session.executingSql || '')}</td>
                    </tr>
                `;
            });
            html += '</tbody></table>';
            container.innerHTML = html;
        }

        // Log Modal functionality
        const logModalOverlay = document.createElement('div');
        logModalOverlay.className = 'log-modal-overlay';
        logModalOverlay.innerHTML = `
            <div class="log-modal">
                <div class="log-modal-header">
                    <h2>Server Logs</h2>
                    <button class="log-modal-close" title="Close">×</button>
                </div>
                <div class="log-modal-content">
                    <textarea class="log-textarea" id="logTextarea" readonly></textarea>
                </div>
            </div>
        `;
        document.body.appendChild(logModalOverlay);

        const logModalCloseBtn = logModalOverlay.querySelector('.log-modal-close');
        const logTextarea = logModalOverlay.querySelector('#logTextarea');

        // Close log modal
        logModalCloseBtn.addEventListener('click', () => {
            logModalOverlay.classList.remove('active');
        });
        logModalOverlay.addEventListener('click', (e) => {
            if (e.target === logModalOverlay) {
                logModalOverlay.classList.remove('active');
            }
        });

        // Function to open log modal and fetch logs
        function openLogModal() {
            logModalOverlay.classList.add('active');
            logTextarea.value = 'Loading logs...';
            fetch('/logfile')
                .then(response => response.text())
                .then(text => {
                    logTextarea.value = text;
                    // Auto-scroll to bottom
                    logTextarea.scrollTop = logTextarea.scrollHeight;
                })
                .catch(err => {
                    logTextarea.value = 'Failed to load logs: ' + err;
                });
        }

        // Override Log Viewer button click to open modal instead of panel
        const logsButton = document.querySelector('.sidebar-btn[data-panel="logs"]');
        if (logsButton) {
            // Remove existing click handler by replacing with new one
            const newLogsButton = logsButton.cloneNode(true);
            logsButton.parentNode.replaceChild(newLogsButton, logsButton);
            newLogsButton.addEventListener('click', (e) => {
                e.preventDefault();
                openLogModal();
                // Update active button (optional)
                document.querySelectorAll('.sidebar-btn').forEach(b => b.classList.remove('active'));
                newLogsButton.classList.add('active');
                // Hide other panels
                document.querySelectorAll('.sidebar-panel').forEach(p => p.classList.remove('active'));
            });
        }

        // Override Status button click to open modal instead of panel
        const statusButton = document.querySelector('.sidebar-btn[data-panel="status"]');
        if (statusButton) {
            // Remove existing click handler by replacing with new one
            const newStatusButton = statusButton.cloneNode(true);
            statusButton.parentNode.replaceChild(newStatusButton, statusButton);
            newStatusButton.addEventListener('click', (e) => {
                e.preventDefault();
                openStatusModal();
                // Update active button (optional)
                document.querySelectorAll('.sidebar-btn').forEach(b => b.classList.remove('active'));
                newStatusButton.classList.add('active');
                // Hide other panels
                document.querySelectorAll('.sidebar-panel').forEach(p => p.classList.remove('active'));
            });
        }

        // Backup Modal functionality
        const backupModalOverlay = document.createElement('div');
        backupModalOverlay.className = 'backup-modal-overlay';
        backupModalOverlay.innerHTML = `
            <div class="backup-modal">
                <div class="backup-modal-header">
                    <h2>Backup Database</h2>
                    <button class="backup-modal-close" title="Close">×</button>
                </div>
                <div class="backup-modal-content">
                    <div class="backup-input-group">
                        <label for="backupFilename">Backup tag (no dots, will be appended with .db)</label>
                        <input type="text" id="backupFilename" placeholder="e.g., mybackup" maxlength="100">
                    </div>
                    <div class="backup-progress">
                        <div class="backup-spinner" id="backupSpinner" style="display:none;"></div>
                        <div class="backup-timer" id="backupTimer">Elapsed: 0s</div>
                    </div>
                    <div class="backup-status" id="backupStatus"></div>
                    <div class="backup-actions">
                        <button class="backup-btn" id="backupStartBtn">Start Backup</button>
                        <button class="backup-btn cancel" id="backupCancelBtn" disabled>Cancel</button>
                        <button class="backup-btn" id="backupCloseBtn" style="display:none;">Close</button>
                    </div>
                </div>
            </div>
        `;
        document.body.appendChild(backupModalOverlay);

        const backupModalCloseBtn = backupModalOverlay.querySelector('.backup-modal-close');
        const backupFilenameInput = backupModalOverlay.querySelector('#backupFilename');
        const backupSpinner = backupModalOverlay.querySelector('#backupSpinner');
        const backupTimer = backupModalOverlay.querySelector('#backupTimer');
        const backupStatus = backupModalOverlay.querySelector('#backupStatus');
        const backupStartBtn = backupModalOverlay.querySelector('#backupStartBtn');
        const backupCancelBtn = backupModalOverlay.querySelector('#backupCancelBtn');
        const backupCloseBtn = backupModalOverlay.querySelector('#backupCloseBtn');

        let backupStartTime = null;
        let backupTimerInterval = null;
        let backupInProgress = false;

        // Close backup modal
        backupModalCloseBtn.addEventListener('click', () => {
            backupModalOverlay.classList.remove('active');
        });
        backupModalOverlay.addEventListener('click', (e) => {
            if (e.target === backupModalOverlay) {
                backupModalOverlay.classList.remove('active');
            }
        });

        // Function to open backup modal
        function openBackupModal() {
            backupModalOverlay.classList.add('active');
            backupFilenameInput.value = '';
            backupSpinner.style.display = 'none';
            backupTimer.textContent = 'Elapsed: 0s';
            backupStatus.textContent = '';
            backupStartBtn.disabled = false;
            backupStartBtn.style.display = 'inline-block';
            backupCancelBtn.disabled = false;
            backupCancelBtn.style.display = 'inline-block';
            backupCloseBtn.style.display = 'none';
            backupInProgress = false;
            if (backupTimerInterval) {
                clearInterval(backupTimerInterval);
                backupTimerInterval = null;
            }
            // Set focus to input after modal is shown, with a small delay to ensure visibility
            setTimeout(() => {
                backupFilenameInput.focus();
                backupFilenameInput.select();
            }, 10);
        }

        // Validate filename (no Windows/Linux path symbols)
        function isValidFilename(filename) {
            if (!filename || filename.trim() === '') return false;
            // Disallow characters that could be used for path traversal
            const forbidden = /[<>:"|?*\\/]/;
            return !forbidden.test(filename);
        }

        // Update timer
        function updateBackupTimer() {
            if (!backupStartTime) return;
            const elapsed = Math.floor((Date.now() - backupStartTime) / 1000);
            backupTimer.textContent = `Elapsed: ${elapsed}s`;
        }

        // Start backup process
        function startBackup() {
            const filename = backupFilenameInput.value.trim();
            if (!isValidFilename(filename)) {
                backupStatus.textContent = 'Invalid filename. Do not use path symbols (<, >, :, ", |, ?, *, \\, /).';
                backupStatus.style.color = '#ff6b6b';
                return;
            }

            backupStartBtn.disabled = true;
            backupCancelBtn.disabled = true;
            backupSpinner.style.display = 'block';
            backupStatus.textContent = 'Starting backup...';
            backupStatus.style.color = '#4fc3f7';
            backupStartTime = Date.now();
            backupTimerInterval = setInterval(updateBackupTimer, 1000);
            backupInProgress = true;

            // Call /backup endpoint
            fetch('/backup', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ backupTag: filename })
            })
            .then(response => {
                if (!response.ok) {
                    return response.text().then(text => {
                        throw new Error(`HTTP ${response.status}: ${text}`);
                    });
                }
                return response.json();
            })
            .then(data => {
                if (data.success) {
                    // Determine the filename for download (use data.filename if provided, otherwise fallback)
                    let downloadPath = data.filename || `backup/${filename}`;
                    // Extract basename for download attribute (strip 'backup/' prefix if present)
                    let downloadBasename = downloadPath.replace(/^backup\//, '');
                    backupStatus.textContent = 'Backup completed. Downloading...';
                    backupStatus.style.color = '#66bb6a';
                    // After backup, call /download to get the file
                    return fetch(`/download?filename=${encodeURIComponent(downloadPath)}`)
                        .then(response => {
                            if (!response.ok) throw new Error('Download failed');
                            return response.blob();
                        })
                        .then(blob => {
                            // Create a download link and trigger download
                            const url = window.URL.createObjectURL(blob);
                            const a = document.createElement('a');
                            a.href = url;
                            a.download = downloadBasename;
                            document.body.appendChild(a);
                            a.click();
                            window.URL.revokeObjectURL(url);
                            document.body.removeChild(a);

                            backupStatus.textContent = 'Backup downloaded successfully.';
                            backupStatus.style.color = '#66bb6a';
                            backupSpinner.style.display = 'none';
                            backupStartBtn.style.display = 'none';
                            backupCancelBtn.disabled = true;
                            backupCancelBtn.style.display = 'none';
                            backupCloseBtn.style.display = 'inline-block';
                            backupInProgress = false;
                            clearInterval(backupTimerInterval);
                        });
                } else {
                    throw new Error(data.error || 'Backup failed');
                }
            })
            .catch(err => {
                backupStatus.textContent = 'Error: ' + err.message;
                backupStatus.style.color = '#ff6b6b';
                backupSpinner.style.display = 'none';
                backupStartBtn.disabled = false;
                backupCancelBtn.disabled = false;
                backupCloseBtn.style.display = 'none';
                backupInProgress = false;
                if (backupTimerInterval) {
                    clearInterval(backupTimerInterval);
                    backupTimerInterval = null;
                }
            });
        }

        // Cancel backup
        function cancelBackup() {
            if (!backupInProgress) {
                backupModalOverlay.classList.remove('active');
                return;
            }
            // Optionally call a cancel endpoint if exists
            backupStatus.textContent = 'Backup cancelled.';
            backupStatus.style.color = '#ff9800';
            backupSpinner.style.display = 'none';
            backupStartBtn.disabled = false;
            backupCancelBtn.disabled = true;
            backupInProgress = false;
            if (backupTimerInterval) {
                clearInterval(backupTimerInterval);
                backupTimerInterval = null;
            }
        }

        // Event listeners
        backupStartBtn.addEventListener('click', startBackup);
        backupCancelBtn.addEventListener('click', cancelBackup);
        backupCloseBtn.addEventListener('click', () => {
            backupModalOverlay.classList.remove('active');
        });

        // Override Backup button click to open modal instead of panel
        const backupButton = document.querySelector('.sidebar-btn[data-panel="backup"]');
        if (backupButton) {
            const newBackupButton = backupButton.cloneNode(true);
            backupButton.parentNode.replaceChild(newBackupButton, backupButton);
            newBackupButton.addEventListener('click', (e) => {
                e.preventDefault();
                openBackupModal();
                // Update active button (optional)
                document.querySelectorAll('.sidebar-btn').forEach(b => b.classList.remove('active'));
                newBackupButton.classList.add('active');
                // Hide other panels
                document.querySelectorAll('.sidebar-panel').forEach(p => p.classList.remove('active'));
            });
        }

        // Also override the existing backup panel's button to open modal
        const panelBackupBtn = document.getElementById('backupBtn');
        if (panelBackupBtn) {
            panelBackupBtn.addEventListener('click', (e) => {
                e.preventDefault();
                openBackupModal();
            });
        }

        // Register Service Modal functionality
        const registerModalOverlay = document.createElement('div');
        registerModalOverlay.className = 'backup-modal-overlay'; // reuse same styling
        registerModalOverlay.innerHTML = `
            <div class="backup-modal">
                <div class="backup-modal-header">
                    <h2>Register New Service</h2>
                    <button class="backup-modal-close" title="Close">×</button>
                </div>
                <div class="backup-modal-content">
                    <div class="backup-input-group">
                        <label for="registerServiceName">Service Name *</label>
                        <input type="text" id="registerServiceName" placeholder="e.g., getUsers" maxlength="100">
                    </div>
                    <div class="backup-input-group">
                        <label for="registerServiceVersion">Service Version *</label>
                        <input type="text" id="registerServiceVersion" placeholder="e.g., v1" maxlength="50">
                    </div>
                    <div class="backup-input-group">
                        <label for="registerCategory">Category *</label>
                        <input type="text" id="registerCategory" placeholder="e.g., analytics" maxlength="100">
                    </div>
                    <div class="backup-input-group">
                        <label for="registerServiceType">Service Type *</label>
                        <select id="registerServiceType">
                            <option value="GET">GET</option>
                            <option value="POST">POST</option>
                        </select>
                    </div>
                    <div class="backup-input-group">
                        <label for="registerSql">SQL Query *</label>
                        <textarea id="registerSql" rows="4" placeholder="SELECT * FROM users WHERE id = \${userId}" style="width:100%; padding:10px; background:#252525; border:1px solid #444; border-radius:4px; color:#f0f0f0; font-family:'Courier New', monospace; font-size:14px;"></textarea>
                    </div>
                    <div class="backup-input-group">
                        <label for="registerDescription">Description</label>
                        <textarea id="registerDescription" rows="2" placeholder="Optional description" style="width:100%; padding:10px; background:#252525; border:1px solid #444; border-radius:4px; color:#f0f0f0; font-family:'Courier New', monospace; font-size:14px;"></textarea>
                    </div>
                    <div class="backup-input-group">
                        <label for="registerSearchPath">Search Path (optional)</label>
                        <input type="text" id="registerSearchPath" placeholder="e.g., public" maxlength="100">
                    </div>
                    <div class="backup-input-group">
                        <label for="registerSnapshotLimit">Snapshot Limit (optional, e.g., 5m, 1h)</label>
                        <input type="text" id="registerSnapshotLimit" placeholder="e.g., 5m" maxlength="20">
                    </div>
                    <div class="backup-input-group">
                        <label for="registerParameters">Parameters (JSON array, optional)</label>
                        <textarea id="registerParameters" rows="3" placeholder='[{"name":"userId","defaultValue":"1"}]' style="width:100%; padding:10px; background:#252525; border:1px solid #444; border-radius:4px; color:#f0f0f0; font-family:'Courier New', monospace; font-size:14px;"></textarea>
                    </div>
                    <div class="backup-status" id="registerStatus"></div>
                    <div class="backup-actions">
                        <button class="backup-btn" id="registerSubmitBtn">Register</button>
                        <button class="backup-btn cancel" id="registerCancelBtn">Cancel</button>
                    </div>
                </div>
            </div>
        `;
        document.body.appendChild(registerModalOverlay);

        const registerModalCloseBtn = registerModalOverlay.querySelector('.backup-modal-close');
        const registerCancelBtn = registerModalOverlay.querySelector('#registerCancelBtn');
        const registerSubmitBtn = registerModalOverlay.querySelector('#registerSubmitBtn');
        const registerStatus = registerModalOverlay.querySelector('#registerStatus');

        // Close register modal
        registerModalCloseBtn.addEventListener('click', () => {
            registerModalOverlay.classList.remove('active');
        });
        registerCancelBtn.addEventListener('click', () => {
            registerModalOverlay.classList.remove('active');
        });
        registerModalOverlay.addEventListener('click', (e) => {
            if (e.target === registerModalOverlay) {
                registerModalOverlay.classList.remove('active');
            }
        });

        // Function to open register modal
        function openRegisterModal() {
            registerModalOverlay.classList.add('active');
            // Clear previous inputs
            document.getElementById('registerServiceName').value = '';
            document.getElementById('registerServiceVersion').value = '';
            document.getElementById('registerCategory').value = '';
            document.getElementById('registerServiceType').value = 'GET';
            document.getElementById('registerSql').value = '';
            document.getElementById('registerDescription').value = '';
            document.getElementById('registerSearchPath').value = '';
            document.getElementById('registerSnapshotLimit').value = '';
            document.getElementById('registerParameters').value = '';
            registerStatus.textContent = '';
            // Focus first input
            setTimeout(() => {
                document.getElementById('registerServiceName').focus();
            }, 10);
        }

        // Submit registration
        registerSubmitBtn.addEventListener('click', () => {
            const serviceName = document.getElementById('registerServiceName').value.trim();
            const serviceVersion = document.getElementById('registerServiceVersion').value.trim();
            const category = document.getElementById('registerCategory').value.trim();
            const serviceType = document.getElementById('registerServiceType').value;
            const sql = document.getElementById('registerSql').value.trim();
            const description = document.getElementById('registerDescription').value.trim();
            const searchPath = document.getElementById('registerSearchPath').value.trim();
            const snapshotLimit = document.getElementById('registerSnapshotLimit').value.trim();
            const parametersStr = document.getElementById('registerParameters').value.trim();

            if (!serviceName || !serviceVersion || !category || !serviceType || !sql) {
                registerStatus.textContent = 'Please fill in required fields (Name, Version, Category, Type, SQL).';
                registerStatus.style.color = '#ff6b6b';
                return;
            }

            // Validate parameters JSON
            let parameters = [];
            if (parametersStr) {
                try {
                    parameters = JSON.parse(parametersStr);
                    if (!Array.isArray(parameters)) {
                        throw new Error('Parameters must be a JSON array');
                    }
                } catch (e) {
                    registerStatus.textContent = 'Invalid parameters JSON: ' + e.message;
                    registerStatus.style.color = '#ff6b6b';
                    return;
                }
            }

            // Build service definition object
            const serviceDef = {
                serviceName,
                serviceVersion,
                category: category || '',
                serviceType,
                sql,
                description: description || 'NO DESCRIPTION.',
                searchPath: searchPath || '',
                snapshotLimit: snapshotLimit || '',
                parameters: parameters
            };

            registerStatus.textContent = 'Registering...';
            registerStatus.style.color = '#4fc3f7';
            registerSubmitBtn.disabled = true;

            fetch('/api/registerService', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(serviceDef)
            })
            .then(response => response.json())
            .then(data => {
                if (data.retCode === 0) {
                    registerStatus.textContent = 'Service registered successfully.';
                    registerStatus.style.color = '#66bb6a';
                    setTimeout(() => {
                        registerModalOverlay.classList.remove('active');
                        output('Service registered successfully.', 'success');
                        loadServiceList();
                    }, 1500);
                } else {
                    registerStatus.textContent = 'Registration failed: ' + data.retMsg;
                    registerStatus.style.color = '#ff6b6b';
                }
            })
            .catch(err => {
                registerStatus.textContent = 'Error: ' + err.message;
                registerStatus.style.color = '#ff6b6b';
            })
            .finally(() => {
                registerSubmitBtn.disabled = false;
            });
        });

        // ==================== Data Service Management ====================
        // Function to update Save button state based on data_service_schema parameter
        function updateSaveButtonState(dataServiceSchema) {
            const saveBtn = document.getElementById('saveServiceBtn');
            if (!saveBtn) return;
            // If dataServiceSchema is missing, empty, or null, disable the button
            if (!dataServiceSchema || dataServiceSchema.trim() === '') {
                saveBtn.disabled = true;
                saveBtn.style.opacity = '0.5';
                saveBtn.style.cursor = 'not-allowed';
                saveBtn.title = 'Save disabled because data_service_schema is not configured';
            } else {
                saveBtn.disabled = false;
                saveBtn.style.opacity = '1';
                saveBtn.style.cursor = 'pointer';
                saveBtn.title = 'Save registered services to file';
            }
        }

        // Global state for selected services
        let selectedServices = new Set(); // stores service names (or IDs)

        // Load service list from API
        function loadServiceList() {
            const tbody = document.getElementById('serviceTableBody');
            const messageEl = document.getElementById('serviceListMessage');
            const countEl = document.getElementById('serviceCount');
            tbody.innerHTML = '';
            messageEl.textContent = 'Loading services...';
            fetch('/api/listRegisteredService')
                .then(response => response.json())
                .then(data => {
                    // Determine the services array based on response format
                    let services = [];
                    if (Array.isArray(data)) {
                        // Direct array response (backend returns JSON array)
                        services = data;
                    } else if (data && data.success && Array.isArray(data.services)) {
                        // Object with success and services fields (legacy format)
                        services = data.services;
                    } else if (data && Array.isArray(data)) {
                        // Fallback: data is already an array (should be caught above)
                        services = data;
                    } else {
                        // Unknown format
                        messageEl.textContent = 'Failed to load services: Unexpected response format';
                        return;
                    }
                    if (services.length === 0) {
                        messageEl.textContent = 'No registered services found.';
                        countEl.textContent = '0 services';
                        return;
                    }
                    messageEl.textContent = '';
                    services.forEach(service => {
                        const row = document.createElement('tr');
                        row.dataset.serviceName = service.serviceName;
                        row.dataset.serviceVersion = service.serviceVersion;
                        // Checkbox cell
                        const checkboxCell = document.createElement('td');
                        const checkbox = document.createElement('input');
                        checkbox.type = 'checkbox';
                        checkbox.className = 'service-checkbox';
                        checkbox.dataset.serviceName = service.serviceName;
                        checkbox.dataset.serviceVersion = service.serviceVersion;
                        checkbox.addEventListener('change', (e) => {
                            const checked = e.target.checked;
                            const serviceKey = `${service.serviceName}@${service.serviceVersion}`;
                            if (checked) {
                                selectedServices.add(serviceKey);
                            } else {
                                selectedServices.delete(serviceKey);
                            }
                            updateSelectAllCheckbox();
                        });
                        checkboxCell.appendChild(checkbox);
                        row.appendChild(checkboxCell);
                        // Service Name
                        const nameCell = document.createElement('td');
                        nameCell.textContent = service.serviceName;
                        row.appendChild(nameCell);
                        // Version
                        const versionCell = document.createElement('td');
                        versionCell.textContent = service.serviceVersion;
                        row.appendChild(versionCell);
                        // Category
                        const categoryCell = document.createElement('td');
                        categoryCell.textContent = service.category || '';
                        row.appendChild(categoryCell);
                        // Type
                        const typeCell = document.createElement('td');
                        typeCell.textContent = service.serviceType || '';
                        row.appendChild(typeCell);
                        // Description
                        const descCell = document.createElement('td');
                        descCell.textContent = service.description || '';
                        row.appendChild(descCell);
                        // Snapshot Limit
                        const limitCell = document.createElement('td');
                        limitCell.textContent = service.snapshotLimit || '';
                        row.appendChild(limitCell);
                        // Parameters
                        const paramsCell = document.createElement('td');
                        paramsCell.textContent = service.parameters ? JSON.stringify(service.parameters) : '';
                        row.appendChild(paramsCell);
                        tbody.appendChild(row);
                    });
                    countEl.textContent = `${services.length} service(s)`;
                    updateSelectAllCheckbox();
                })
                .catch(err => {
                    console.error('Error loading services:', err);
                    messageEl.textContent = 'Error loading services: ' + err.message;
                });
        }

        // Update "Select All" checkbox state
        function updateSelectAllCheckbox() {
            const selectAll = document.getElementById('selectAllCheckbox');
            const checkboxes = document.querySelectorAll('.service-checkbox');
            if (checkboxes.length === 0) {
                selectAll.checked = false;
                selectAll.indeterminate = false;
                return;
            }
            const checkedCount = Array.from(checkboxes).filter(cb => cb.checked).length;
            if (checkedCount === 0) {
                selectAll.checked = false;
                selectAll.indeterminate = false;
            } else if (checkedCount === checkboxes.length) {
                selectAll.checked = true;
                selectAll.indeterminate = false;
            } else {
                selectAll.checked = false;
                selectAll.indeterminate = true;
            }
        }

        // Select all / deselect all
        document.getElementById('selectAllCheckbox').addEventListener('change', function(e) {
            const checked = e.target.checked;
            const checkboxes = document.querySelectorAll('.service-checkbox');
            selectedServices.clear();
            checkboxes.forEach(cb => {
                cb.checked = checked;
                if (checked) {
                    const serviceKey = `${cb.dataset.serviceName}@${cb.dataset.serviceVersion}`;
                    selectedServices.add(serviceKey);
                }
            });
        });

        // Refresh button
        const refreshBtn = document.getElementById('refreshServiceBtn');
        if (refreshBtn) refreshBtn.addEventListener('click', loadServiceList);

        // Load button (load from file)
        document.getElementById('loadServiceBtn').addEventListener('click', function() {
            // For simplicity, we'll use a file input dialog
            const input = document.createElement('input');
            input.type = 'file';
            input.accept = '.service,.json';
            input.onchange = (e) => {
                const file = e.target.files[0];
                if (!file) return;
                const reader = new FileReader();
                reader.onload = (event) => {
                    const content = event.target.result;
                    // Send raw content as request body (as the API expects)
                    fetch('/api/loadRegisterService', {
                        method: 'POST',
                        headers: { 'Content-Type': 'application/json' },
                        body: content
                    })
                    .then(response => response.json())
                    .then(data => {
                        if (data.retCode === 0) {
                            output('Service loaded successfully.', 'success');
                            loadServiceList();
                        } else {
                            output('Failed to load service: ' + data.retMsg, 'error');
                        }
                    })
                    .catch(err => {
                        output('Error loading service: ' + err.message, 'error');
                    });
                };
                reader.readAsText(file);
            };
            input.click();
        });

        // Save button (save to file)
        document.getElementById('saveServiceBtn').addEventListener('click', function() {
            output('Saving all registered services to file...', 'message');
            fetch('/api/saveRegisterService', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({})  // no parameters needed
            })
            .then(response => response.json())
            .then(data => {
                if (data.success) {
                    output('All services saved successfully. Saved path: ' + (data.savedPath || 'unknown'), 'success');
                    showToast('Save successful');
                } else {
                    output('Failed to save services: ' + data.error, 'error');
                    showToast('Save failed: ' + data.error, true);
                }
            })
            .catch(err => {
                output('Error saving services: ' + err.message, 'error');
                showToast('Save error: ' + err.message, true);
            });
        });

        // Download button (download service definition)
        document.getElementById('downloadServiceBtn').addEventListener('click', function() {
            output('Downloading all registered services as JSON file...', 'message');
            fetch('/api/downloadRegisteredService', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({})  // no parameters needed
            })
                .then(response => {
                    if (!response.ok) throw new Error('Download failed: ' + response.status);
                    return response.blob();
                })
                .then(blob => {
                    // Use file picker dialog (or fallback)
                    saveFileWithPicker(blob, 'services.json')
                        .then(() => {
                            // Success: no message displayed
                        })
                        .catch(err => {
                            output('Error saving file: ' + err.message, 'error');
                        });
                })
                .catch(err => {
                    output('Error downloading services: ' + err.message, 'error');
                });
        });

        // Register button (open registration dialog)
        document.getElementById('registerServiceBtn').addEventListener('click', function() {
            openRegisterModal();
        });

        // Unregister button (unregister selected services)
        document.getElementById('unregisterServiceBtn').addEventListener('click', function() {
            if (selectedServices.size === 0) {
                output('No service selected.', 'error');
                showToast('No service selected', true);
                return;
            }
            if (!confirm(`Are you sure you want to unregister ${selectedServices.size} service(s)?`)) {
                return;
            }
            const promises = [];
            selectedServices.forEach(serviceKey => {
                const [serviceName, serviceVersion] = serviceKey.split('@');
                promises.push(
                    fetch('/api/unRegisterService', {
                        method: 'POST',
                        headers: { 'Content-Type': 'application/json' },
                        body: JSON.stringify({ serviceName, serviceVersion })
                    })
                    .then(response => response.json())
                    .then(data => {
                        if (!data.success) {
                            throw new Error(`Failed to unregister ${serviceKey}: ${data.error}`);
                        }
                    })
                );
            });
            Promise.all(promises)
                .then(() => {
                    output(`Successfully unregistered ${selectedServices.size} service(s).`, 'success');
                    showToast(`Successfully unregistered ${selectedServices.size} service(s)`);
                    selectedServices.clear();
                    loadServiceList();
                })
                .catch(err => {
                    output('Error during unregister: ' + err.message, 'error');
                });
        });

        // Registration Modal is now defined elsewhere (after backup modal)
        // The function openRegisterModal() opens a proper form dialog.

        // Load service list when data service panel becomes active
        const dataServiceBtn = document.querySelector('.sidebar-btn[data-panel="data-service"]');
        if (dataServiceBtn) {
            dataServiceBtn.addEventListener('click', () => {
                // Small delay to ensure panel is visible
                setTimeout(loadServiceList, 100);
            });
        }

        // Initial load if panel is already active (if user navigates directly)
        if (document.getElementById('panel-data-service').classList.contains('active')) {
            loadServiceList();
        }

        // ==================== MCP Tool Management ====================
        // Global state for selected MCP tools
        let selectedMCPTools = new Set(); // stores tool names

        // Load MCP Tool list from API
        function loadMCPToolList() {
            const tbody = document.getElementById('mcpToolTableBody');
            const messageEl = document.getElementById('mcpToolListMessage');
            const countEl = document.getElementById('mcpToolCount');
            tbody.innerHTML = '';
            messageEl.textContent = 'Loading MCP Tools...';
            console.log('Loading MCP tools...');
            // Use JSON-RPC endpoint /jsonrpc with method "tools/list"
            fetch('/jsonrpc', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({
                    jsonrpc: '2.0',
                    id: 1,
                    method: 'tools/list',
                    params: {}
                })
            })
                .then(response => response.json())
                .then(data => {
                    console.log('MCP tools response:', data);
                    if (data.error) {
                        messageEl.textContent = 'Error: ' + data.error.message;
                        return;
                    }
                    const tools = data.result.tools || [];
                    console.log('Found tools:', tools);
                    if (tools.length === 0) {
                        messageEl.textContent = 'No MCP tools found.';
                        countEl.textContent = '0 tools';
                        return;
                    }
                    messageEl.textContent = '';
                    tools.forEach(tool => {
                        const row = document.createElement('tr');
                        row.dataset.toolName = tool.name;
                        // Checkbox cell
                        const checkboxCell = document.createElement('td');
                        const checkbox = document.createElement('input');
                        checkbox.type = 'checkbox';
                        checkbox.className = 'mcp-tool-checkbox';
                        checkbox.dataset.toolName = tool.name;
                        checkbox.addEventListener('change', (e) => {
                            const checked = e.target.checked;
                            if (checked) {
                                selectedMCPTools.add(tool.name);
                            } else {
                                selectedMCPTools.delete(tool.name);
                            }
                            updateSelectAllMCPToolCheckbox();
                        });
                        checkboxCell.appendChild(checkbox);
                        row.appendChild(checkboxCell);
                        // Tool Name
                        const nameCell = document.createElement('td');
                        nameCell.textContent = tool.name;
                        row.appendChild(nameCell);
                        // Description
                        const descCell = document.createElement('td');
                        descCell.textContent = tool.description || '';
                        row.appendChild(descCell);
                        // Category
                        const categoryCell = document.createElement('td');
                        categoryCell.textContent = tool.category || '';
                        row.appendChild(categoryCell);
                        // Input Schema (shortened)
                        const schemaCell = document.createElement('td');
                        schemaCell.textContent = tool.inputSchema ? JSON.stringify(tool.inputSchema).substring(0, 50) + '...' : '';
                        row.appendChild(schemaCell);
                        tbody.appendChild(row);
                    });
                    console.log('Appended', tools.length, 'rows to table');
                    countEl.textContent = `${tools.length} tool(s)`;
                    updateSelectAllMCPToolCheckbox();
                })
                .catch(err => {
                    console.error('Error loading MCP tools:', err);
                    messageEl.textContent = 'Error loading MCP tools: ' + err.message;
                });
        }

        // Update "Select All" checkbox state for MCP tools
        function updateSelectAllMCPToolCheckbox() {
            const selectAll = document.getElementById('selectAllMCPToolCheckbox');
            const checkboxes = document.querySelectorAll('.mcp-tool-checkbox');
            if (checkboxes.length === 0) {
                selectAll.checked = false;
                selectAll.indeterminate = false;
                return;
            }
            const checkedCount = Array.from(checkboxes).filter(cb => cb.checked).length;
            if (checkedCount === 0) {
                selectAll.checked = false;
                selectAll.indeterminate = false;
            } else if (checkedCount === checkboxes.length) {
                selectAll.checked = true;
                selectAll.indeterminate = false;
            } else {
                selectAll.checked = false;
                selectAll.indeterminate = true;
            }
        }

        // Select all / deselect all for MCP tools
        document.getElementById('selectAllMCPToolCheckbox').addEventListener('change', function(e) {
            const checked = e.target.checked;
            const checkboxes = document.querySelectorAll('.mcp-tool-checkbox');
            selectedMCPTools.clear();
            checkboxes.forEach(cb => {
                cb.checked = checked;
                if (checked) {
                    selectedMCPTools.add(cb.dataset.toolName);
                }
            });
        });

        // Load MCP Tool button
        document.getElementById('loadMCPToolBtn').addEventListener('click', function() {
            const input = document.createElement('input');
            input.type = 'file';
            input.accept = '.json,.tool';
            input.onchange = (e) => {
                const file = e.target.files[0];
                if (!file) return;
                const reader = new FileReader();
                reader.onload = (event) => {
                    const content = event.target.result;
                    fetch('/mcp/loadMCPTool', {
                        method: 'POST',
                        headers: { 'Content-Type': 'application/json' },
                        body: content
                    })
                    .then(response => response.json())
                    .then(data => {
                        if (data.retCode === 0) {
                            output('MCP Tools loaded successfully.', 'success');
                            loadMCPToolList();
                        } else {
                            output('Failed to load MCP Tools: ' + data.retMsg, 'error');
                        }
                    })
                    .catch(err => {
                        output('Error loading MCP Tools: ' + err.message, 'error');
                    });
                };
                reader.readAsText(file);
            };
            input.click();
        });

        // Save MCP Tool button
        document.getElementById('saveMCPToolBtn').addEventListener('click', function() {
            output('Saving MCP Tools to file...', 'message');
            fetch('/mcp/saveMCPTool', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({})
            })
            .then(response => response.json())
            .then(data => {
                if (data.retCode === 0) {
                    output('MCP Tools saved successfully. Saved path: ' + (data.savedPath || 'unknown'), 'success');
                    showToast('Save successful');
                } else {
                    output('Failed to save MCP Tools: ' + data.retMsg, 'error');
                    showToast('Save failed: ' + data.retMsg, true);
                }
            })
            .catch(err => {
                output('Error saving MCP Tools: ' + err.message, 'error');
                showToast('Save error: ' + err.message, true);
            });
        });

        // Download MCP Tool button
        document.getElementById('downloadMCPToolBtn').addEventListener('click', function() {
            output('Downloading MCP Tools as JSON file...', 'message');
            fetch('/mcp/dumpMCPTool', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({})
            })
                .then(response => {
                    if (!response.ok) throw new Error('Download failed: ' + response.status);
                    return response.blob();
                })
                .then(blob => {
                    // Use file picker dialog (or fallback)
                    saveFileWithPicker(blob, 'mcp_tools.json')
                        .then(() => {
                            // Success: no message displayed
                        })
                        .catch(err => {
                            output('Error saving file: ' + err.message, 'error');
                        });
                })
                .catch(err => {
                    output('Error downloading MCP Tools: ' + err.message, 'error');
                });
        });

        // Register MCP Tool button (open modal)
        document.getElementById('registerMCPToolBtn').addEventListener('click', function() {
            openMCPToolModal();
        });

        // Unregister MCP Tool button
        document.getElementById('unregisterMCPToolBtn').addEventListener('click', function() {
            if (selectedMCPTools.size === 0) {
                output('No MCP tool selected.', 'error');
                showToast('No MCP tool selected', true);
                return;
            }
            if (!confirm(`Are you sure you want to unregister ${selectedMCPTools.size} MCP tool(s)?`)) {
                return;
            }
            const promises = [];
            selectedMCPTools.forEach(toolName => {
                promises.push(
                    fetch('/mcp/unregisterMCPTool', {
                        method: 'POST',
                        headers: { 'Content-Type': 'application/json' },
                        body: JSON.stringify({ name: toolName })
                    })
                    .then(response => response.json())
                    .then(data => {
                        if (data.retCode !== 0) {
                            throw new Error(`Failed to unregister ${toolName}: ${data.retMsg}`);
                        }
                    })
                );
            });
            Promise.all(promises)
                .then(() => {
                    output(`Successfully unregistered ${selectedMCPTools.size} MCP tool(s).`, 'success');
                    showToast(`Successfully unregistered ${selectedMCPTools.size} MCP tool(s)`);
                    selectedMCPTools.clear();
                    loadMCPToolList();
                })
                .catch(err => {
                    output('Error during unregister: ' + err.message, 'error');
                });
        });

        // Load MCP Tool list when panel becomes active
        const mcpToolBtn = document.querySelector('.sidebar-btn[data-panel="mcp-tool"]');
        if (mcpToolBtn) {
            mcpToolBtn.addEventListener('click', () => {
                setTimeout(loadMCPToolList, 100);
            });
        }

        // ==================== MCP Tool Registration Modal ====================
        const mcpToolModalOverlay = document.createElement('div');
        mcpToolModalOverlay.className = 'backup-modal-overlay'; // reuse same styling
        mcpToolModalOverlay.innerHTML = `
            <div class="backup-modal">
                <div class="backup-modal-header">
                    <h2>Register New MCP Tool</h2>
                    <button class="backup-modal-close" title="Close">×</button>
                </div>
                <div class="backup-modal-content">
                    <div class="backup-input-group">
                        <label for="mcpToolName">Tool Name *</label>
                        <input type="text" id="mcpToolName" placeholder="e.g., get_weather" maxlength="100">
                    </div>
                    <div class="backup-input-group">
                        <label for="mcpToolDescription">Description *</label>
                        <textarea id="mcpToolDescription" rows="2" placeholder="Description of the tool" style="width:100%; padding:10px; background:#252525; border:1px solid #444; border-radius:4px; color:#f0f0f0; font-family:'Courier New', monospace; font-size:14px;"></textarea>
                    </div>
                    <div class="backup-input-group">
                        <label for="mcpToolInputSchema">Input Schema (JSON) *</label>
                        <textarea id="mcpToolInputSchema" rows="4" placeholder='{"type": "object", "properties": {"city": {"type": "string"}}}' style="width:100%; padding:10px; background:#252525; border:1px solid #444; border-radius:4px; color:#f0f0f0; font-family:'Courier New', monospace; font-size:14px;"></textarea>
                    </div>
                    <div class="backup-input-group">
                        <label for="mcpToolCategory">Category *</label>
                        <input type="text" id="mcpToolCategory" placeholder="e.g., weather" maxlength="100">
                    </div>
                    <div class="backup-status" id="mcpToolStatus"></div>
                    <div class="backup-actions">
                        <button class="backup-btn" id="mcpToolSubmitBtn">Register</button>
                        <button class="backup-btn cancel" id="mcpToolCancelBtn">Cancel</button>
                    </div>
                </div>
            </div>
        `;
        document.body.appendChild(mcpToolModalOverlay);

        const mcpToolModalCloseBtn = mcpToolModalOverlay.querySelector('.backup-modal-close');
        const mcpToolCancelBtn = mcpToolModalOverlay.querySelector('#mcpToolCancelBtn');
        const mcpToolSubmitBtn = mcpToolModalOverlay.querySelector('#mcpToolSubmitBtn');
        const mcpToolStatus = mcpToolModalOverlay.querySelector('#mcpToolStatus');

        // Close modal
        mcpToolModalCloseBtn.addEventListener('click', () => {
            mcpToolModalOverlay.classList.remove('active');
        });
        mcpToolCancelBtn.addEventListener('click', () => {
            mcpToolModalOverlay.classList.remove('active');
        });
        mcpToolModalOverlay.addEventListener('click', (e) => {
            if (e.target === mcpToolModalOverlay) {
                mcpToolModalOverlay.classList.remove('active');
            }
        });

        // Function to open MCP Tool registration modal
        function openMCPToolModal() {
            mcpToolModalOverlay.classList.add('active');
            // Clear previous inputs
            document.getElementById('mcpToolName').value = '';
            document.getElementById('mcpToolDescription').value = '';
            document.getElementById('mcpToolInputSchema').value = '';
            document.getElementById('mcpToolCategory').value = '';
            mcpToolStatus.textContent = '';
            // Focus first input
            setTimeout(() => {
                document.getElementById('mcpToolName').focus();
            }, 10);
        }

        // Submit registration
        mcpToolSubmitBtn.addEventListener('click', () => {
            const name = document.getElementById('mcpToolName').value.trim();
            const description = document.getElementById('mcpToolDescription').value.trim();
            const inputSchemaStr = document.getElementById('mcpToolInputSchema').value.trim();
            const category = document.getElementById('mcpToolCategory').value.trim();

            if (!name || !description || !inputSchemaStr || !category) {
                mcpToolStatus.textContent = 'Please fill in required fields (Name, Description, Input Schema, Category).';
                mcpToolStatus.style.color = '#ff6b6b';
                return;
            }

            // Validate input schema JSON
            let inputSchema;
            try {
                inputSchema = JSON.parse(inputSchemaStr);
                if (typeof inputSchema !== 'object' || inputSchema === null) {
                    throw new Error('Input schema must be a JSON object');
                }
            } catch (e) {
                mcpToolStatus.textContent = 'Invalid input schema JSON: ' + e.message;
                mcpToolStatus.style.color = '#ff6b6b';
                return;
            }

            // Build tool definition object
            const toolDef = {
                name,
                description,
                inputSchema,
                category: category || ''
            };

            mcpToolStatus.textContent = 'Registering...';
            mcpToolStatus.style.color = '#4fc3f7';
            mcpToolSubmitBtn.disabled = true;

            fetch('/mcp/registerMCPTool', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(toolDef)
            })
            .then(response => response.json())
            .then(data => {
                if (data.retCode === 0) {
                    mcpToolStatus.textContent = 'MCP Tool registered successfully.';
                    mcpToolStatus.style.color = '#66bb6a';
                    setTimeout(() => {
                        mcpToolModalOverlay.classList.remove('active');
                        output('MCP Tool registered successfully.', 'success');
                        loadMCPToolList();
                    }, 1500);
                } else {
                    mcpToolStatus.textContent = 'Registration failed: ' + data.retMsg;
                    mcpToolStatus.style.color = '#ff6b6b';
                }
            })
            .catch(err => {
                mcpToolStatus.textContent = 'Error: ' + err.message;
                mcpToolStatus.style.color = '#ff6b6b';
            })
            .finally(() => {
                mcpToolSubmitBtn.disabled = false;
            });
        });

        // ==================== MCP Resource Management ====================
        // Global state for selected MCP resources
        let selectedMCPResources = new Set(); // stores resource URIs

        // Load MCP Resource list from API
        function loadMCPResourceList() {
            const tbody = document.getElementById('mcpResourceTableBody');
            const messageEl = document.getElementById('mcpResourceListMessage');
            const countEl = document.getElementById('mcpResourceCount');
            tbody.innerHTML = '';
            messageEl.textContent = 'Loading MCP Resources...';
            // Use JSON-RPC endpoint /jsonrpc with method "resources/list"
            fetch('/jsonrpc', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({
                    jsonrpc: '2.0',
                    id: 1,
                    method: 'resources/list',
                    params: {}
                })
            })
                .then(response => response.json())
                .then(data => {
                    if (data.error) {
                        messageEl.textContent = 'Error: ' + data.error.message;
                        return;
                    }
                    const resources = data.result.resources || [];
                    if (resources.length === 0) {
                        messageEl.textContent = 'No MCP resources found.';
                        countEl.textContent = '0 resources';
                        return;
                    }
                    messageEl.textContent = '';
                    resources.forEach(res => {
                        const row = document.createElement('tr');
                        row.dataset.resourceUri = res.uri;
                        // Checkbox cell
                        const checkboxCell = document.createElement('td');
                        const checkbox = document.createElement('input');
                        checkbox.type = 'checkbox';
                        checkbox.className = 'mcp-resource-checkbox';
                        checkbox.dataset.resourceUri = res.uri;
                        checkbox.addEventListener('change', (e) => {
                            const checked = e.target.checked;
                            if (checked) {
                                selectedMCPResources.add(res.uri);
                            } else {
                                selectedMCPResources.delete(res.uri);
                            }
                            updateSelectAllMCPResourceCheckbox();
                        });
                        checkboxCell.appendChild(checkbox);
                        row.appendChild(checkboxCell);
                        // URI
                        const uriCell = document.createElement('td');
                        uriCell.textContent = res.uri;
                        row.appendChild(uriCell);
                        // Name
                        const nameCell = document.createElement('td');
                        nameCell.textContent = res.name || '';
                        row.appendChild(nameCell);
                        // Description
                        const descCell = document.createElement('td');
                        descCell.textContent = res.description || '';
                        row.appendChild(descCell);
                        // MIME Type
                        const mimeCell = document.createElement('td');
                        mimeCell.textContent = res.mimeType || '';
                        row.appendChild(mimeCell);
                        // Category
                        const categoryCell = document.createElement('td');
                        categoryCell.textContent = res.category || '';
                        row.appendChild(categoryCell);
                        tbody.appendChild(row);
                    });
                    countEl.textContent = `${resources.length} resource(s)`;
                    updateSelectAllMCPResourceCheckbox();
                })
                .catch(err => {
                    console.error('Error loading MCP resources:', err);
                    messageEl.textContent = 'Error loading MCP resources: ' + err.message;
                });
        }

        // Update "Select All" checkbox state for MCP resources
        function updateSelectAllMCPResourceCheckbox() {
            const selectAll = document.getElementById('selectAllMCPResourceCheckbox');
            const checkboxes = document.querySelectorAll('.mcp-resource-checkbox');
            if (checkboxes.length === 0) {
                selectAll.checked = false;
                selectAll.indeterminate = false;
                return;
            }
            const checkedCount = Array.from(checkboxes).filter(cb => cb.checked).length;
            if (checkedCount === 0) {
                selectAll.checked = false;
                selectAll.indeterminate = false;
            } else if (checkedCount === checkboxes.length) {
                selectAll.checked = true;
                selectAll.indeterminate = false;
            } else {
                selectAll.checked = false;
                selectAll.indeterminate = true;
            }
        }

        // Select all / deselect all for MCP resources
        document.getElementById('selectAllMCPResourceCheckbox').addEventListener('change', function(e) {
            const checked = e.target.checked;
            const checkboxes = document.querySelectorAll('.mcp-resource-checkbox');
            selectedMCPResources.clear();
            checkboxes.forEach(cb => {
                cb.checked = checked;
                if (checked) {
                    selectedMCPResources.add(cb.dataset.resourceUri);
                }
            });
        });

        // Load MCP Resource button
        document.getElementById('loadMCPResourceBtn').addEventListener('click', function() {
            const input = document.createElement('input');
            input.type = 'file';
            input.accept = '.json,.resource';
            input.onchange = (e) => {
                const file = e.target.files[0];
                if (!file) return;
                const reader = new FileReader();
                reader.onload = (event) => {
                    const content = event.target.result;
                    fetch('/mcp/loadMCPResource', {
                        method: 'POST',
                        headers: { 'Content-Type': 'application/json' },
                        body: content
                    })
                    .then(response => response.json())
                    .then(data => {
                        if (data.retCode === 0) {
                            output('MCP Resources loaded successfully.', 'success');
                            loadMCPResourceList();
                        } else {
                            output('Failed to load MCP Resources: ' + data.retMsg, 'error');
                        }
                    })
                    .catch(err => {
                        output('Error loading MCP Resources: ' + err.message, 'error');
                    });
                };
                reader.readAsText(file);
            };
            input.click();
        });

        // Save MCP Resource button
        document.getElementById('saveMCPResourceBtn').addEventListener('click', function() {
            output('Saving MCP Resources to file...', 'message');
            fetch('/mcp/saveMCPSource', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({})
            })
            .then(response => response.json())
            .then(data => {
                if (data.retCode === 0) {
                    output('MCP Resources saved successfully. Saved path: ' + (data.savedPath || 'unknown'), 'success');
                    showToast('Save successful');
                } else {
                    output('Failed to save MCP Resources: ' + data.retMsg, 'error');
                    showToast('Save failed: ' + data.retMsg, true);
                }
            })
            .catch(err => {
                output('Error saving MCP Resources: ' + err.message, 'error');
                showToast('Save error: ' + err.message, true);
            });
        });

        // Download MCP Resource button
        document.getElementById('downloadMCPResourceBtn').addEventListener('click', function() {
            output('Downloading MCP Resources as JSON file...', 'message');
            fetch('/mcp/dumpMCPSource', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({})
            })
                .then(response => {
                    if (!response.ok) throw new Error('Download failed: ' + response.status);
                    return response.blob();
                })
                .then(blob => {
                    // Use file picker dialog (or fallback)
                    saveFileWithPicker(blob, 'mcp_resources.json')
                        .then(() => {
                            // Success: no message displayed
                        })
                        .catch(err => {
                            output('Error saving file: ' + err.message, 'error');
                        });
                })
                .catch(err => {
                    output('Error downloading MCP Resources: ' + err.message, 'error');
                });
        });

        // Register MCP Resource button (open modal)
        document.getElementById('registerMCPResourceBtn').addEventListener('click', function() {
            openMCPResourceModal();
        });

        // Unregister MCP Resource button
        document.getElementById('unregisterMCPResourceBtn').addEventListener('click', function() {
            if (selectedMCPResources.size === 0) {
                output('No MCP resource selected.', 'error');
                showToast('No MCP resource selected', true);
                return;
            }
            if (!confirm(`Are you sure you want to unregister ${selectedMCPResources.size} MCP resource(s)?`)) {
                return;
            }
            const promises = [];
            selectedMCPResources.forEach(resourceUri => {
                promises.push(
                    fetch('/mcp/unregisterMCPSource', {
                        method: 'POST',
                        headers: { 'Content-Type': 'application/json' },
                        body: JSON.stringify({ uri: resourceUri })
                    })
                    .then(response => response.json())
                    .then(data => {
                        if (data.retCode !== 0) {
                            throw new Error(`Failed to unregister ${resourceUri}: ${data.retMsg}`);
                        }
                    })
                );
            });
            Promise.all(promises)
                .then(() => {
                    output(`Successfully unregistered ${selectedMCPResources.size} MCP resource(s).`, 'success');
                    showToast(`Successfully unregistered ${selectedMCPResources.size} MCP resource(s)`);
                    selectedMCPResources.clear();
                    loadMCPResourceList();
                })
                .catch(err => {
                    output('Error during unregister: ' + err.message, 'error');
                });
        });

        // Load MCP Resource list when panel becomes active
        const mcpResourceBtn = document.querySelector('.sidebar-btn[data-panel="mcp-resource"]');
        if (mcpResourceBtn) {
            mcpResourceBtn.addEventListener('click', () => {
                setTimeout(loadMCPResourceList, 100);
            });
        }

        // ==================== MCP Resource Registration Modal ====================
        const mcpResourceModalOverlay = document.createElement('div');
        mcpResourceModalOverlay.className = 'backup-modal-overlay'; // reuse same styling
        mcpResourceModalOverlay.innerHTML = `
            <div class="backup-modal">
                <div class="backup-modal-header">
                    <h2>Register New MCP Resource</h2>
                    <button class="backup-modal-close" title="Close">×</button>
                </div>
                <div class="backup-modal-content">
                    <div class="backup-input-group">
                        <label for="mcpResourceUri">Resource URI *</label>
                        <input type="text" id="mcpResourceUri" placeholder="e.g., file:///data/example.json" maxlength="500">
                    </div>
                    <div class="backup-input-group">
                        <label for="mcpResourceName">Name *</label>
                        <input type="text" id="mcpResourceName" placeholder="e.g., Example Data" maxlength="100">
                    </div>
                    <div class="backup-input-group">
                        <label for="mcpResourceDescription">Description *</label>
                        <textarea id="mcpResourceDescription" rows="2" placeholder="Description of the resource" style="width:100%; padding:10px; background:#252525; border:1px solid #444; border-radius:4px; color:#f0f0f0; font-family:'Courier New', monospace; font-size:14px;"></textarea>
                    </div>
                    <div class="backup-input-group">
                        <label for="mcpResourceMimeType">MIME Type *</label>
                        <input type="text" id="mcpResourceMimeType" placeholder="e.g., application/json" maxlength="100">
                    </div>
                    <div class="backup-input-group">
                        <label for="mcpResourceContents">Contents (JSON) *</label>
                        <textarea id="mcpResourceContents" rows="4" placeholder='{"key": "value"}' style="width:100%; padding:10px; background:#252525; border:1px solid #444; border-radius:4px; color:#f0f0f0; font-family:'Courier New', monospace; font-size:14px;"></textarea>
                    </div>
                    <div class="backup-input-group">
                        <label for="mcpResourceCategory">Category *</label>
                        <input type="text" id="mcpResourceCategory" placeholder="e.g., data" maxlength="100">
                    </div>
                    <div class="backup-status" id="mcpResourceStatus"></div>
                    <div class="backup-actions">
                        <button class="backup-btn" id="mcpResourceSubmitBtn">Register</button>
                        <button class="backup-btn cancel" id="mcpResourceCancelBtn">Cancel</button>
                    </div>
                </div>
            </div>
        `;
        document.body.appendChild(mcpResourceModalOverlay);

        const mcpResourceModalCloseBtn = mcpResourceModalOverlay.querySelector('.backup-modal-close');
        const mcpResourceCancelBtn = mcpResourceModalOverlay.querySelector('#mcpResourceCancelBtn');
        const mcpResourceSubmitBtn = mcpResourceModalOverlay.querySelector('#mcpResourceSubmitBtn');
        const mcpResourceStatus = mcpResourceModalOverlay.querySelector('#mcpResourceStatus');

        // Close modal
        mcpResourceModalCloseBtn.addEventListener('click', () => {
            mcpResourceModalOverlay.classList.remove('active');
        });
        mcpResourceCancelBtn.addEventListener('click', () => {
            mcpResourceModalOverlay.classList.remove('active');
        });
        mcpResourceModalOverlay.addEventListener('click', (e) => {
            if (e.target === mcpResourceModalOverlay) {
                mcpResourceModalOverlay.classList.remove('active');
            }
        });

        // Function to open MCP Resource registration modal
        function openMCPResourceModal() {
            mcpResourceModalOverlay.classList.add('active');
            // Clear previous inputs
            document.getElementById('mcpResourceUri').value = '';
            document.getElementById('mcpResourceName').value = '';
            document.getElementById('mcpResourceDescription').value = '';
            document.getElementById('mcpResourceMimeType').value = '';
            document.getElementById('mcpResourceContents').value = '';
            document.getElementById('mcpResourceCategory').value = '';
            mcpResourceStatus.textContent = '';
            // Focus first input
            setTimeout(() => {
                document.getElementById('mcpResourceUri').focus();
            }, 10);
        }

        // Submit registration
        mcpResourceSubmitBtn.addEventListener('click', () => {
            const uri = document.getElementById('mcpResourceUri').value.trim();
            const name = document.getElementById('mcpResourceName').value.trim();
            const description = document.getElementById('mcpResourceDescription').value.trim();
            const mimeType = document.getElementById('mcpResourceMimeType').value.trim();
            const contentsStr = document.getElementById('mcpResourceContents').value.trim();
            const category = document.getElementById('mcpResourceCategory').value.trim();

            if (!uri || !name || !description || !mimeType || !contentsStr || !category) {
                mcpResourceStatus.textContent = 'Please fill in required fields (URI, Name, Description, MIME Type, Contents, Category).';
                mcpResourceStatus.style.color = '#ff6b6b';
                return;
            }

            // Validate contents JSON
            let contents;
            try {
                contents = JSON.parse(contentsStr);
                // contents can be any JSON value (string, number, object, array)
            } catch (e) {
                mcpResourceStatus.textContent = 'Invalid contents JSON: ' + e.message;
                mcpResourceStatus.style.color = '#ff6b6b';
                return;
            }

            // Build resource definition object
            const resourceDef = {
                uri,
                name,
                description,
                mimeType,
                contents,
                category: category || ''
            };

            mcpResourceStatus.textContent = 'Registering...';
            mcpResourceStatus.style.color = '#4fc3f7';
            mcpResourceSubmitBtn.disabled = true;

            fetch('/mcp/registerMCPResource', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(resourceDef)
            })
            .then(response => response.json())
            .then(data => {
                if (data.retCode === 0) {
                    mcpResourceStatus.textContent = 'MCP Resource registered successfully.';
                    mcpResourceStatus.style.color = '#66bb6a';
                    setTimeout(() => {
                        mcpResourceModalOverlay.classList.remove('active');
                        output('MCP Resource registered successfully.', 'success');
                        loadMCPResourceList();
                    }, 1500);
                } else {
                    mcpResourceStatus.textContent = 'Registration failed: ' + data.retMsg;
                    mcpResourceStatus.style.color = '#ff6b6b';
                }
            })
            .catch(err => {
                mcpResourceStatus.textContent = 'Error: ' + err.message;
                mcpResourceStatus.style.color = '#ff6b6b';
            })
            .finally(() => {
                mcpResourceSubmitBtn.disabled = false;
            });
        });