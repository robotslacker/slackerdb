import { EditorView, basicSetup } from 'codemirror';
import { keymap } from '@codemirror/view';
import { Compartment, EditorState, Prec } from '@codemirror/state';
import { sql } from '@codemirror/lang-sql';

/**
 * CodeMirror 6 的最小 Vaadin 包装件（M0-V3）。
 *
 * 约定（与 Java 侧 `org.slackerdb.dbdesk.jsbridge.SqlEditor` 一一对应）：
 *  - 属性 `sql`      ：服务端 → 客户端，设置编辑器内容；
 *  - 属性 `readOnly` ：服务端 → 客户端，切换只读；
 *  - 事件 `run`      ：Ctrl/Cmd+Enter 触发，detail.value 是当前 SQL；
 *  - 事件 `change`   ：内容变化（300 ms 防抖），detail.value 是当前 SQL。
 *
 * **为什么用 shadow DOM**：CodeMirror 用"构造式样式表"注入基础样式（document.adoptedStyleSheets），
 * 而 Vaadin 的主题（`@StyleSheet(Aura.STYLESHEET)`）也往同一个数组里塞自己的样式表 —— 实测两者
 * 互相覆盖，结果是编辑器**完全没有布局**（gutters 撑满整行、正文被裁到可视区外）。
 * 把编辑器放进自己的 shadow root，并把 `root: this.shadowRoot` 交给 EditorView，
 * 它的样式就落在 shadow root 的 adoptedStyleSheets 上，与页面的主题互不干扰。
 *
 * 内容仍可被测试读取：Playwright 的选择器会自动穿透 open shadow root（`.cm-editor` 等照常可用）。
 */
class SqlEditorElement extends HTMLElement {

    private view: EditorView | null = null;
    private editableCompartment = new Compartment();
    private changeTimer: number | null = null;
    private resizeObserver: ResizeObserver | null = null;

    private sqlValue = '';
    private readOnlyValue = false;

    // Vaadin 会在元素升级前就 setProperty，所以属性必须用访问器声明（不用 Lit）
    get sql(): string {
        return this.view ? this.view.state.doc.toString() : this.sqlValue;
    }

    set sql(value: string) {
        this.sqlValue = value ?? '';
        if (this.view && this.sqlValue !== this.view.state.doc.toString()) {
            this.view.dispatch({
                changes: { from: 0, to: this.view.state.doc.length, insert: this.sqlValue },
                selection: { anchor: 0 }
            });
        }
    }

    get readOnly(): boolean {
        return this.readOnlyValue;
    }

    set readOnly(value: boolean) {
        this.readOnlyValue = !!value;
        if (this.view) {
            this.view.dispatch({
                effects: this.editableCompartment.reconfigure([
                    EditorState.readOnly.of(this.readOnlyValue),
                    EditorView.editable.of(!this.readOnlyValue)
                ])
            });
        }
    }

    connectedCallback(): void {
        if (this.view) {
            return;
        }
        this.classList.add('sql-editor-host');
        // 宿主自己撑住高度并裁掉溢出；高度由 syncHeight() 显式同步给编辑器
        this.style.display = 'block';
        this.style.overflow = 'hidden';
        this.style.border = '1px solid #d5d9e0';
        this.style.borderRadius = '4px';

        const root = this.shadowRoot ?? this.attachShadow({ mode: 'open' });
        root.innerHTML = '<div class="editor-host" style="height:100%;min-height:80px;"></div>';
        const host = root.querySelector('.editor-host') as HTMLElement;

        const runKeymap = Prec.highest(keymap.of([{
            key: 'Ctrl-Enter',
            mac: 'Cmd-Enter',
            run: () => {
                const selection = this.view?.state.selection.main;
                const selected = selection && !selection.empty
                    ? this.view!.state.sliceDoc(selection.from, selection.to)
                    : '';
                this.fire('run', selected);
                return true;
            }
        }]));

        this.view = new EditorView({
            doc: this.sqlValue,
            root,
            extensions: [
                basicSetup,
                sql(),
                runKeymap,
                this.editableCompartment.of([
                    EditorState.readOnly.of(this.readOnlyValue),
                    EditorView.editable.of(!this.readOnlyValue)
                ]),
                EditorView.updateListener.of(update => {
                    if (update.docChanged) {
                        this.sqlValue = update.state.doc.toString();
                        this.fireDebouncedChange();
                    }
                }),
                EditorView.theme({
                    '&': { height: '100%', fontSize: '13px' },
                    '.cm-scroller': { overflow: 'auto', fontFamily: 'Consolas, "Courier New", monospace' }
                })
            ],
            parent: host
        });

        this.syncHeight();
        if (typeof ResizeObserver !== 'undefined') {
            this.resizeObserver = new ResizeObserver(() => this.syncHeight());
            this.resizeObserver.observe(this);
        }
    }

    disconnectedCallback(): void {
        this.resizeObserver?.disconnect();
        this.resizeObserver = null;
        if (this.view) {
            this.view.destroy();
            this.view = null;
        }
    }

    /** 供排查/测试用：当前内容。 */
    getValue(): string {
        return this.sql;
    }

    private syncHeight(): void {
        if (!this.view) {
            return;
        }
        const target = Math.max(80, this.clientHeight);
        this.view.dom.style.height = target + 'px';
    }

    private fire(name: string, value: string = this.sql): void {
        // shadow DOM 里 dispatch 的事件要 composed 才能穿透到 Vaadin 的监听器
        this.dispatchEvent(new CustomEvent(name, {
            detail: { value },
            bubbles: true,
            composed: true
        }));
    }

    private fireDebouncedChange(): void {
        if (this.changeTimer !== null) {
            window.clearTimeout(this.changeTimer);
        }
        this.changeTimer = window.setTimeout(() => {
            this.changeTimer = null;
            this.fire('change');
        }, 300);
    }
}

customElements.define('sql-editor', SqlEditorElement);

export { SqlEditorElement };
