import {ExperienceRuntime, type RuntimeSnapshot} from './runtime.js';

/**
 * 仅合并高频正文的视图发布，不缓冲协议事件或延迟执行决策。
 * Runtime 始终同步保存最新值；终态、审批、取消等更新立即覆盖待发布帧。
 * 最后一个订阅退出时清理计时器，避免旧界面重新唤醒。
 */
export class RuntimePresentation {
  #visible: RuntimeSnapshot;
  #timer: ReturnType<typeof setTimeout> | undefined;
  #unsubscribe: (() => void) | undefined;
  readonly #listeners = new Set<() => void>();
  constructor(private readonly runtime: ExperienceRuntime) {this.#visible = runtime.snapshot();}
  snapshot = (): RuntimeSnapshot => this.#visible;
  subscribe = (listener: () => void): (() => void) => {
    this.#listeners.add(listener);
    if (!this.#unsubscribe) {
      this.#visible = this.runtime.snapshot();
      this.#unsubscribe = this.runtime.subscribe(streaming => {
        if (!streaming) {this.publish(); return;}
        // 最大等待一个短帧，不使用可无限延后的debounce；慢流也必须持续可见。
        this.#timer ??= setTimeout(() => this.publish(), 50);
      });
    }
    return () => {
      this.#listeners.delete(listener);
      if (this.#listeners.size) return;
      this.#unsubscribe?.(); this.#unsubscribe = undefined;
      clearTimeout(this.#timer); this.#timer = undefined;
    };
  };
  private publish(): void {
    clearTimeout(this.#timer); this.#timer = undefined;
    this.#visible = this.runtime.snapshot();
    for (const listener of this.#listeners) listener();
  }
}
