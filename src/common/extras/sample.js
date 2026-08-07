/**
 * 预制扩展包示例（与读取路线改为 import .js，规避真机 @system.file.readText 对应用资源路径报202）
 * 内容与原 sample.json 同。import 走 knowledgeData.js 同一条路，真机已验证可用。
 * 后期新增扩展包：在 extras/ 下新建 xxx.js、加入 store 的 extras 注册表即可。
 */
export default {
  "拓展物理": [
    {
      "desc": "示例扩展条目：狭义相对论的基本假设与时间膨胀效应。",
      "formulas": [
        "t = t0 / sqrt(1 - v^2/c^2)",
        "L = L0 * sqrt(1 - v^2/c^2)"
      ],
      "id": 1,
      "points": [
        "光速不变原理：真空中光速对任何惯性观察者恒为 c",
        "相对性原理：物理定律在所有惯性系中形式相同",
        "运动钟变慢：相对静止观察者，运动钟走得慢"
      ],
      "title": "相对论初步"
    },
    {
      "desc": "示例扩展条目：动量守恒与冲量-动量定理。",
      "formulas": [
        "p = mv",
        "I = Δp = Ft"
      ],
      "id": 2,
      "points": [
        "动量 p = mv，方向与速度方向相同",
        "冲量 I = Ft，等于动量的变化量",
        "系统不受外力时总动量守恒"
      ],
      "title": "动量与冲量"
    }
  ]
}
