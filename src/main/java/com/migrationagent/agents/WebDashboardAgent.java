package com.migrationagent.agents;

import com.migrationagent.core.AgentResult;
import com.migrationagent.core.MigrationContext;
import com.migrationagent.util.ConsoleUI;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Spawns a local embedded HTTP server to display a beautiful, 
 * modern web dashboard containing the migration report, diffs, and AI reviews.
 */
public class WebDashboardAgent implements Agent {

    private static final Logger log = LoggerFactory.getLogger(WebDashboardAgent.class);

    @Override
    public String getName() {
        return "Web Dashboard";
    }

    @Override
    public AgentResult execute(MigrationContext context) {
        long start = System.currentTimeMillis();
        ConsoleUI.agentStart(getName());

        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(8080), 0);
            
            // Serve the HTML dashboard
            server.createContext("/", new DashboardHandler());
            
            // Serve the JSON report data
            server.createContext("/api/data", new DataHandler(context.getProjectPath()));

            server.setExecutor(null); 
            server.start();

            ConsoleUI.success("=========================================================");
            ConsoleUI.success("  🚀 LEGENDARY WEB DASHBOARD LIVE");
            ConsoleUI.success("  => http://localhost:8080/");
            ConsoleUI.success("  Press Ctrl+C to stop the server and exit.");
            ConsoleUI.success("=========================================================");

            // Keep the thread alive so the server runs until user kills it
            // Only do this if it's the final step
            Thread.currentThread().join();

        } catch (Exception e) {
            log.error("Failed to start web dashboard", e);
            return AgentResult.builder(getName())
                    .status(AgentResult.Status.FAILURE)
                    .message("Failed to start server: " + e.getMessage())
                    .durationMs(System.currentTimeMillis() - start)
                    .build();
        }

        return AgentResult.builder(getName())
                .status(AgentResult.Status.SUCCESS)
                .message("Dashboard served")
                .durationMs(System.currentTimeMillis() - start)
                .build();
    }

    private static class DashboardHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange t) throws IOException {
            String response = getHtmlTemplate();
            t.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
            t.sendResponseHeaders(200, response.getBytes().length);
            try (OutputStream os = t.getResponseBody()) {
                os.write(response.getBytes());
            }
        }
    }

    private static class DataHandler implements HttpHandler {
        private final Path projectPath;

        public DataHandler(Path projectPath) {
            this.projectPath = projectPath;
        }

        @Override
        public void handle(HttpExchange t) throws IOException {
            Path jsonFile = projectPath.resolve("migration-report.json");
            String response = "{}";
            if (Files.exists(jsonFile)) {
                response = Files.readString(jsonFile);
            }

            // CORS headers
            t.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
            t.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
            t.sendResponseHeaders(200, response.getBytes().length);
            try (OutputStream os = t.getResponseBody()) {
                os.write(response.getBytes());
            }
        }
    }

    private static String getHtmlTemplate() {
        return """
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>JDK Migration Dashboard</title>
    <style>
        :root {
            --bg-dark: #0f172a;
            --bg-card: rgba(30, 41, 59, 0.7);
            --text-main: #f8fafc;
            --text-muted: #94a3b8;
            --accent: #3b82f6;
            --success: #10b981;
            --warning: #f59e0b;
            --danger: #ef4444;
            --glass-border: rgba(255, 255, 255, 0.1);
        }
        body {
            background: var(--bg-dark);
            color: var(--text-main);
            font-family: 'Inter', system-ui, sans-serif;
            margin: 0;
            padding: 2rem;
            min-height: 100vh;
            background-image: 
                radial-gradient(circle at 15% 50%, rgba(59, 130, 246, 0.15) 0%, transparent 50%),
                radial-gradient(circle at 85% 30%, rgba(16, 185, 129, 0.15) 0%, transparent 50%);
        }
        .container {
            max-width: 1200px;
            margin: 0 auto;
        }
        header {
            text-align: center;
            margin-bottom: 3rem;
            animation: fadeInDown 0.8s ease-out;
        }
        h1 {
            font-size: 3rem;
            background: linear-gradient(to right, #60a5fa, #34d399);
            -webkit-background-clip: text;
            -webkit-text-fill-color: transparent;
            margin: 0 0 1rem 0;
        }
        .stats-grid {
            display: grid;
            grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
            gap: 1.5rem;
            margin-bottom: 3rem;
        }
        .stat-card {
            background: var(--bg-card);
            backdrop-filter: blur(12px);
            border: 1px solid var(--glass-border);
            border-radius: 16px;
            padding: 1.5rem;
            text-align: center;
            box-shadow: 0 4px 6px -1px rgba(0, 0, 0, 0.1);
            transition: transform 0.3s ease;
            animation: fadeInUp 0.8s ease-out backwards;
        }
        .stat-card:hover {
            transform: translateY(-5px);
        }
        .stat-value {
            font-size: 2.5rem;
            font-weight: bold;
            margin-bottom: 0.5rem;
        }
        .stat-label {
            color: var(--text-muted);
            font-size: 0.9rem;
            text-transform: uppercase;
            letter-spacing: 1px;
        }
        
        .section-title {
            font-size: 1.8rem;
            border-bottom: 1px solid var(--glass-border);
            padding-bottom: 0.5rem;
            margin-top: 3rem;
            margin-bottom: 1.5rem;
        }

        .diff-card {
            background: var(--bg-card);
            border: 1px solid var(--glass-border);
            border-radius: 12px;
            margin-bottom: 2rem;
            overflow: hidden;
            animation: fadeInUp 1s ease-out backwards;
        }
        .diff-header {
            background: rgba(0,0,0,0.3);
            padding: 1rem 1.5rem;
            font-weight: bold;
            display: flex;
            justify-content: space-between;
            align-items: center;
            border-bottom: 1px solid var(--glass-border);
        }
        .ai-review {
            background: rgba(59, 130, 246, 0.1);
            border-left: 4px solid var(--accent);
            padding: 1rem 1.5rem;
            font-size: 0.95rem;
            line-height: 1.5;
        }
        .ai-review strong {
            color: #60a5fa;
        }
        .code-container {
            display: flex;
        }
        .code-pane {
            flex: 1;
            padding: 1rem;
            overflow-x: auto;
            font-family: 'Consolas', monospace;
            font-size: 0.9rem;
            white-space: pre;
            line-height: 1.5;
        }
        .code-pane.original {
            background: rgba(239, 68, 68, 0.05);
            border-right: 1px solid var(--glass-border);
        }
        .code-pane.modified {
            background: rgba(16, 185, 129, 0.05);
        }
        
        @keyframes fadeInDown {
            from { opacity: 0; transform: translateY(-20px); }
            to { opacity: 1; transform: translateY(0); }
        }
        @keyframes fadeInUp {
            from { opacity: 0; transform: translateY(20px); }
            to { opacity: 1; transform: translateY(0); }
        }
    </style>
</head>
<body>
    <div class="container">
        <header>
            <h1>JDK Migration Dashboard</h1>
            <p id="subtitle" style="color: var(--text-muted); font-size: 1.2rem;">Loading data...</p>
        </header>

        <div class="stats-grid" id="statsGrid">
            <!-- Stats will be injected here -->
        </div>

        <h2 class="section-title">AI Code Reviews & Diffs</h2>
        <div id="diffsContainer">
            <!-- Diffs will be injected here -->
        </div>
    </div>

    <script>
        async function loadData() {
            try {
                const response = await fetch('/api/data');
                const data = await response.json();
                
                const summary = data.migrationSummary;
                document.getElementById('subtitle').textContent = 
                    `Migration: JDK ${summary.sourceVersion} → JDK ${summary.targetVersion} | Duration: ${(summary.durationMs / 1000).toFixed(1)}s`;

                // Render Stats
                document.getElementById('statsGrid').innerHTML = `
                    <div class="stat-card" style="animation-delay: 0.1s">
                        <div class="stat-value" style="color: var(--accent)">${summary.filesAnalyzed}</div>
                        <div class="stat-label">Files Analyzed</div>
                    </div>
                    <div class="stat-card" style="animation-delay: 0.2s">
                        <div class="stat-value" style="color: var(--success)">${summary.filesModified}</div>
                        <div class="stat-label">Files Modified</div>
                    </div>
                    <div class="stat-card" style="animation-delay: 0.3s">
                        <div class="stat-value" style="color: var(--warning)">${summary.resolvedIssues}</div>
                        <div class="stat-label">Issues Resolved</div>
                    </div>
                    <div class="stat-card" style="animation-delay: 0.4s">
                        <div class="stat-value" style="color: var(--danger)">${summary.unresolvedIssues}</div>
                        <div class="stat-label">Remaining Issues</div>
                    </div>
                `;

                // Render Diffs
                const diffsContainer = document.getElementById('diffsContainer');
                const changes = data.fileChanges || [];
                
                if (changes.length === 0) {
                    diffsContainer.innerHTML = '<p style="color: var(--text-muted);">No file changes were made.</p>';
                    return;
                }

                diffsContainer.innerHTML = changes.map((file, idx) => `
                    <div class="diff-card" style="animation-delay: ${0.2 * (idx + 1)}s">
                        <div class="diff-header">
                            <span>📄 ${file.fileName}</span>
                        </div>
                        ${file.aiReview ? `
                        <div class="ai-review">
                            <strong>✨ AI Architect Review:</strong> ${file.aiReview}
                        </div>
                        ` : ''}
                        <div class="code-container">
                            <div class="code-pane original">${escapeHtml(file.originalCode)}</div>
                            <div class="code-pane modified">${escapeHtml(file.modifiedCode)}</div>
                        </div>
                    </div>
                `).join('');

            } catch (err) {
                document.getElementById('subtitle').textContent = 'Error loading data from /api/data';
                console.error(err);
            }
        }

        function escapeHtml(unsafe) {
            if (!unsafe) return "/* No Original Data */";
            return unsafe
                 .replace(/&/g, "&amp;")
                 .replace(/</g, "&lt;")
                 .replace(/>/g, "&gt;")
                 .replace(/"/g, "&quot;")
                 .replace(/'/g, "&#039;");
        }

        window.onload = loadData;
    </script>
</body>
</html>
""";
    }
}
