package run.halo.astrahub.util;

/**
 * AstraHub 同步/推送运行阶段常量。
 *
 * <p>这些常量对应后端 publish 的 phase 字段。
 * 修改时需要保持前后端一致（前端请参见 console/src/types/index.ts）。</p>
 */
public final class HubPhase {

    private HubPhase() {
    }

    /** 初始化（插件刚启动、尚未执行任何循环）。 */
    public static final String INIT = "INIT";

    /** 启动中（调度器正在启动）。 */
    public static final String STARTING = "STARTING";

    /** 空闲（已启动但当前周期尚未触发）。 */
    public static final String IDLE = "IDLE";

    /** 执行中（推送正在进行）。 */
    public static final String RUNNING = "RUNNING";

    /** 成功（上一次推送成功完成）。 */
    public static final String SUCCESS = "SUCCESS";

    /** 失败（上一次推送出错）。 */
    public static final String ERROR = "ERROR";

    /** 忙碌（已有任务在跑、拒绝新触发）。 */
    public static final String BUSY = "BUSY";

    /** 已停止（@PreDestroy 调用后）。 */
    public static final String STOPPED = "STOPPED";

    /** 未知（异常兜底）。 */
    public static final String UNKNOWN = "UNKNOWN";
}
