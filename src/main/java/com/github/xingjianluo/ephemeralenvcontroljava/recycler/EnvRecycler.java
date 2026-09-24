package com.github.xingjianluo.ephemeralenvcontroljava.recycler;

/**
 * 环境回收器：模拟真实回收动作（释放计算资源等），可能抛异常表示回收中途失败。
 * 实现必须本地可运行，不得依赖外部服务。
 */
public interface EnvRecycler {
    /**
     * 执行回收。
     * @param envId 环境标识
     * @param generation 发起回收时的租约代际，用于识别迟到回调
     */
    void recycle(String envId, long generation) throws Exception;
}
