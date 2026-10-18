// WebSocket JSON-RPC client for the MCTest mod. Connects lazily, retries briefly, and reconnects after the game
// restarts. Uses Node's built-in WebSocket (Node 22+).

export class RemoteError extends Error {
  constructor(message: string, readonly type: string | undefined, readonly remoteStack: string | undefined) {
    super(message);
  }
}

interface Pending {
  resolve: (value: unknown) => void;
  reject: (reason: Error) => void;
  timer: ReturnType<typeof setTimeout>;
}

export class MCTestClient {
  private ws: WebSocket | undefined;
  private connecting: Promise<WebSocket> | undefined;
  private nextId = 1;
  private readonly pending = new Map<number, Pending>();

  constructor(
    readonly port: number,
    private readonly notReachableHint = 'Minecraft client not reachable at {url} ({error}). ' +
      'Start it with ./gradlew :<loader>:runClient (fabric, neoforge or forge; with MCTest on the runtime classpath), ' +
      'then call mc_wait_ready.',
  ) {}

  get url(): string {
    return `ws://127.0.0.1:${this.port}/`;
  }

  /** Calls a method, connecting first if needed (retrying for up to `connectRetryMs`). */
  async call<T = any>(method: string, params: Record<string, unknown> = {}, timeoutMs = 60_000, connectRetryMs = 3_000): Promise<T> {
    const ws = await this.ensureConnected(connectRetryMs);
    const id = this.nextId++;
    return new Promise<T>((resolve, reject) => {
      const timer = setTimeout(() => {
        this.pending.delete(id);
        reject(new Error(`${method} timed out after ${timeoutMs}ms (the game may be frozen or still loading)`));
      }, timeoutMs);
      this.pending.set(id, { resolve: resolve as (v: unknown) => void, reject, timer });
      ws.send(JSON.stringify({ id, method, params }));
    });
  }

  private async ensureConnected(retryMs: number): Promise<WebSocket> {
    if (this.ws && this.ws.readyState === WebSocket.OPEN) return this.ws;
    const deadline = Date.now() + retryMs;
    let lastError: unknown;
    do {
      try {
        this.connecting ??= this.connectOnce();
        return await this.connecting;
      } catch (e) {
        lastError = e;
        this.connecting = undefined;
        await new Promise((r) => setTimeout(r, 500));
      }
    } while (Date.now() < deadline);
    throw new Error(
      this.notReachableHint.replace('{url}', this.url).replace('{error}', String((lastError as Error)?.message ?? lastError)),
    );
  }

  private connectOnce(): Promise<WebSocket> {
    return new Promise((resolve, reject) => {
      const ws = new WebSocket(this.url);
      let opened = false;
      ws.addEventListener('open', () => {
        opened = true;
        this.ws = ws;
        this.connecting = undefined;
        resolve(ws);
      });
      ws.addEventListener('error', () => {
        if (!opened) reject(new Error('connection refused'));
      });
      ws.addEventListener('close', () => {
        if (this.ws === ws) this.ws = undefined;
        this.connecting = undefined;
        for (const [id, p] of this.pending) {
          clearTimeout(p.timer);
          p.reject(new Error('Connection to Minecraft closed: the game exited or crashed (mc_crash_report shows the newest crash report)'));
          this.pending.delete(id);
        }
        if (!opened) reject(new Error('connection closed'));
      });
      ws.addEventListener('message', (ev) => {
        let msg: any;
        try {
          msg = JSON.parse(String(ev.data));
        } catch {
          return;
        }
        const p = this.pending.get(msg.id);
        if (!p) return;
        this.pending.delete(msg.id);
        clearTimeout(p.timer);
        if (msg.error) p.reject(new RemoteError(msg.error.message, msg.error.type, msg.error.stack));
        else p.resolve(msg.result);
      });
    });
  }
}
