import { workflow } from './workflow.mjs';
import { lifecycle } from './lifecycle.mjs';
import { finish } from './client-runtime.mjs';

// 场景由真实 JVM 宿主测试选择，未识别场景拒绝执行，不操作用户配置。
try {
    if (process.argv[2] === 'workflow') await workflow();
    else if (process.argv[2] === 'lifecycle') await lifecycle();
    else throw new Error('unknown-acceptance-scenario');
    await finish(true);
} catch {
    await finish(false);
}
