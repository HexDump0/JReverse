<script lang="ts" module>
  export interface PromptRequest {
    title: string;
    value: string;
    placeholder: string;
    /** What Enter does, e.g. "rename". */
    verb: string;
    /** Shown under the input; say what an empty value does. */
    hint: string;
    multiline?: boolean;
    /** Returns an error to show instead of closing, or nothing. */
    validate?: (value: string) => string | null;
    onsubmit: (value: string) => void;
  }
</script>

<script lang="ts">
  // A one-field dialog where the palette sits: rename, comment, go to line.
  let { req, onclose }: { req: PromptRequest; onclose: () => void } = $props();

  let value = $state("");
  let error = $state<string | null>(null);
  let field = $state<HTMLInputElement | HTMLTextAreaElement>();

  $effect(() => {
    value = req.value;
    error = null;
    queueMicrotask(() => field?.select());
  });

  function submit() {
    const v = value.trim();
    const problem = req.validate?.(v) ?? null;
    if (problem) {
      error = problem;
      return;
    }
    // Closing clears `req`, so hold on to it.
    const { onsubmit } = req;
    onclose();
    onsubmit(v);
  }

  function onkeydown(e: KeyboardEvent) {
    if (e.key === "Escape") {
      e.preventDefault();
      e.stopPropagation();
      onclose();
    } else if (e.key === "Enter" && (!req.multiline || !e.shiftKey)) {
      e.preventDefault();
      submit();
    }
  }
</script>

<svelte:window onmousedown={(e) => !(e.target as Element).closest(".prompt") && onclose()} />

<div class="prompt" role="dialog" aria-label={req.title}>
  <div class="title">{req.title}</div>
  {#if req.multiline}
    <textarea bind:this={field} bind:value {onkeydown} placeholder={req.placeholder} rows="3" spellcheck="true"></textarea>
  {:else}
    <input bind:this={field} bind:value {onkeydown} oninput={() => (error = null)} placeholder={req.placeholder} spellcheck="false" autocomplete="off" />
  {/if}
  <div class="foot">
    {#if error}<span class="err">{error}</span>{:else}<span>{req.hint}</span>{/if}
    <span class="keys"><span class="key">Enter</span> {req.verb}<span class="key">Esc</span> cancel</span>
  </div>
</div>

<style>
  .prompt {
    position: fixed;
    z-index: 60;
    top: 84px;
    left: 50%;
    transform: translateX(-50%);
    width: min(520px, calc(100% - 32px));
    background: var(--raised);
    border-radius: 10px;
    box-shadow: var(--shadow);
    overflow: hidden;
  }
  .title {
    padding: 12px 18px 0;
    font-size: 12.5px;
    color: var(--text-3);
  }
  input,
  textarea {
    display: block;
    width: 100%;
    padding: 8px 18px 12px;
    border: 0;
    border-bottom: 1px solid var(--line);
    background: transparent;
    font: 15px var(--font-code);
    color: var(--text-hi);
    outline: none;
    resize: none;
  }
  textarea {
    font: 14px/1.5 var(--font-ui);
  }
  .foot {
    display: flex;
    align-items: center;
    gap: 16px;
    padding: 9px 18px;
    font-size: 12px;
    color: var(--text-3);
  }
  .err {
    color: var(--bad);
  }
  .keys {
    margin-left: auto;
    display: flex;
    align-items: center;
    gap: 6px;
    white-space: nowrap;
  }
  .keys .key + .key {
    margin-left: 10px;
  }
</style>
