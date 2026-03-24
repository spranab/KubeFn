import * as vscode from 'vscode';

/** Known HeapKeys from kubefn-contracts, used as fallback when HeapKeys.java is not found. */
const KNOWN_HEAP_KEYS: { name: string; value: string; type: string; description: string }[] = [
    { name: 'AUTH_CONTEXT', value: 'auth:{userId}', type: 'AuthContext', description: 'User identity, roles, permissions' },
    { name: 'PRICING_CURRENT', value: 'pricing:current', type: 'PricingResult', description: 'Base price, discount, final price' },
    { name: 'INVENTORY_STATUS', value: 'inventory:{sku}', type: 'InventoryStatus', description: 'Stock levels, warehouse' },
    { name: 'FRAUD_RESULT', value: 'fraud:result', type: 'FraudScore', description: 'Risk score, approved flag' },
    { name: 'SHIPPING_ESTIMATE', value: 'shipping:estimate', type: 'ShippingEstimate', description: 'Method, cost, ETA' },
    { name: 'TAX_CALCULATED', value: 'tax:calculated', type: 'TaxCalculation', description: 'Subtotal, rate, total' },
];

export class HeapKeysCompletionProvider implements vscode.CompletionItemProvider {
    private discoveredKeys: { name: string; value: string; type: string; description: string }[] | null = null;

    async provideCompletionItems(
        document: vscode.TextDocument,
        position: vscode.Position,
        _token: vscode.CancellationToken,
        _context: vscode.CompletionContext
    ): Promise<vscode.CompletionItem[] | undefined> {
        const linePrefix = document.lineAt(position).text.substring(0, position.character);

        if (!linePrefix.endsWith('HeapKeys.')) {
            return undefined;
        }

        const keys = await this.getHeapKeys();

        return keys.map(key => {
            const item = new vscode.CompletionItem(key.name, vscode.CompletionItemKind.Constant);
            item.detail = `"${key.value}" -> ${key.type}`;
            item.documentation = new vscode.MarkdownString(
                `**HeapExchange Key**\n\n` +
                `- **Key:** \`${key.value}\`\n` +
                `- **Type:** \`${key.type}\`\n` +
                `- ${key.description}`
            );
            item.sortText = `0_${key.name}`; // Sort these first
            return item;
        });
    }

    private async getHeapKeys(): Promise<typeof KNOWN_HEAP_KEYS> {
        if (this.discoveredKeys) {
            return this.discoveredKeys;
        }

        // Try to find HeapKeys.java in the workspace
        const files = await vscode.workspace.findFiles('**/HeapKeys.java', '**/node_modules/**', 5);

        if (files.length > 0) {
            try {
                const content = await vscode.workspace.fs.readFile(files[0]);
                const text = Buffer.from(content).toString('utf8');
                const parsed = this.parseHeapKeysFile(text);
                if (parsed.length > 0) {
                    this.discoveredKeys = parsed;
                    return parsed;
                }
            } catch {
                // Fall through to defaults
            }
        }

        return KNOWN_HEAP_KEYS;
    }

    private parseHeapKeysFile(content: string): typeof KNOWN_HEAP_KEYS {
        const results: typeof KNOWN_HEAP_KEYS = [];
        // Match: public static final String KEY_NAME = "key:value";
        const pattern = /public\s+static\s+final\s+String\s+(\w+)\s*=\s*"([^"]+)"/g;
        let match;

        while ((match = pattern.exec(content)) !== null) {
            const name = match[1];
            const value = match[2];
            results.push({
                name,
                value,
                type: this.inferType(name),
                description: `Heap key: ${value}`
            });
        }

        return results;
    }

    private inferType(keyName: string): string {
        const lower = keyName.toLowerCase();
        if (lower.includes('auth')) { return 'AuthContext'; }
        if (lower.includes('pricing')) { return 'PricingResult'; }
        if (lower.includes('inventory')) { return 'InventoryStatus'; }
        if (lower.includes('fraud')) { return 'FraudScore'; }
        if (lower.includes('shipping')) { return 'ShippingEstimate'; }
        if (lower.includes('tax')) { return 'TaxCalculation'; }
        return 'Object';
    }
}
