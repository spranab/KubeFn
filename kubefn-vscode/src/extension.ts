import * as vscode from 'vscode';
import { HeapExplorerProvider } from './heapExplorer';
import { FunctionsExplorerProvider } from './functionsExplorer';
import { HeapKeysCompletionProvider } from './completionProvider';

let statusBarItem: vscode.StatusBarItem;

export function activate(context: vscode.ExtensionContext) {
    const config = vscode.workspace.getConfiguration('kubefn');
    const organismUrl = config.get<string>('organismUrl', 'http://localhost:8081');

    // Heap Explorer sidebar
    const heapProvider = new HeapExplorerProvider(organismUrl);
    vscode.window.registerTreeDataProvider('kubefn.heapExplorer', heapProvider);

    // Functions Explorer sidebar
    const functionsProvider = new FunctionsExplorerProvider(organismUrl);
    vscode.window.registerTreeDataProvider('kubefn.functions', functionsProvider);

    // Commands
    context.subscriptions.push(
        vscode.commands.registerCommand('kubefn.refreshHeap', () => {
            heapProvider.refresh();
            functionsProvider.refresh();
        })
    );

    context.subscriptions.push(
        vscode.commands.registerCommand('kubefn.showTrace', async () => {
            const traceId = await vscode.window.showInputBox({
                prompt: 'Enter trace ID',
                placeHolder: 'e.g. abc-123-def'
            });
            if (traceId) {
                const uri = vscode.Uri.parse(`${organismUrl}/admin/trace/${traceId}`);
                vscode.env.openExternal(uri);
            }
        })
    );

    context.subscriptions.push(
        vscode.commands.registerCommand('kubefn.newFunction', async () => {
            const name = await vscode.window.showInputBox({
                prompt: 'Function class name',
                placeHolder: 'e.g. PricingFunction'
            });
            if (!name) { return; }

            const group = await vscode.window.showInputBox({
                prompt: 'Function group (@FnGroup)',
                placeHolder: 'e.g. pricing-service'
            });
            if (!group) { return; }

            const path = await vscode.window.showInputBox({
                prompt: 'Route path',
                placeHolder: 'e.g. /api/pricing'
            });
            if (!path) { return; }

            const snippet = new vscode.SnippetString([
                `package com.kubefn.functions;`,
                ``,
                `import com.kubefn.api.*;`,
                `import com.kubefn.contracts.*;`,
                `import java.util.Map;`,
                ``,
                `@FnRoute(path = "${path}", methods = {"GET", "POST"})`,
                `@FnGroup("${group}")`,
                `public class ${name} implements KubeFnHandler, FnContextAware {`,
                `    private FnContext ctx;`,
                ``,
                `    @Override`,
                `    public void setContext(FnContext context) { this.ctx = context; }`,
                ``,
                `    @Override`,
                `    public KubeFnResponse handle(KubeFnRequest request) throws Exception {`,
                `        \$0`,
                `        return KubeFnResponse.ok(Map.of("status", "ok"));`,
                `    }`,
                `}`,
                ``
            ].join('\n'));

            const doc = await vscode.workspace.openTextDocument({
                language: 'java',
                content: ''
            });
            const editor = await vscode.window.showTextDocument(doc);
            editor.insertSnippet(snippet);
        })
    );

    // HeapKeys autocomplete for Java/Kotlin
    const completionProvider = new HeapKeysCompletionProvider();
    context.subscriptions.push(
        vscode.languages.registerCompletionItemProvider(
            [{ language: 'java' }, { language: 'kotlin' }],
            completionProvider,
            '.'
        )
    );

    // Status bar
    statusBarItem = vscode.window.createStatusBarItem(vscode.StatusBarAlignment.Left, 50);
    statusBarItem.text = '$(circuit-board) KubeFn';
    statusBarItem.tooltip = `KubeFn organism: ${organismUrl}`;
    statusBarItem.command = 'kubefn.refreshHeap';
    statusBarItem.show();
    context.subscriptions.push(statusBarItem);

    // Check connectivity on startup
    checkOrganism(organismUrl);
}

async function checkOrganism(url: string): Promise<void> {
    try {
        const response = await fetch(`${url}/admin/status`);
        if (response.ok) {
            statusBarItem.text = '$(circuit-board) KubeFn: Connected';
            statusBarItem.backgroundColor = undefined;
        } else {
            statusBarItem.text = '$(circuit-board) KubeFn: Offline';
            statusBarItem.backgroundColor = new vscode.ThemeColor('statusBarItem.warningBackground');
        }
    } catch {
        statusBarItem.text = '$(circuit-board) KubeFn: Offline';
        statusBarItem.backgroundColor = new vscode.ThemeColor('statusBarItem.warningBackground');
    }
}

export function deactivate() {
    if (statusBarItem) {
        statusBarItem.dispose();
    }
}
