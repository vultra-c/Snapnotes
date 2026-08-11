/**
 * 页面转场动画工具（复刻小米固件 SCR_LOAD_ANIM 屏幕转场，依据 ai-use 仓库逆向资料）
 *
 * - 压栈进入 OVER_LEFT ：translateX(40px) -> 0，opacity 0 -> 1，320ms cubic-bezier(0.2,0,0,1)
 * - 返回     OVER_RIGHT：translateX(-40px) -> 0，opacity 0 -> 1，320ms cubic-bezier(0.2,0,0,1)
 *
 * 用法（每个 .ux 页面）：
 *   1. import pageAnim from '../../common/utils/pageAnim.js'
 *   2. private 中加：animStyle: pageAnim.enterStyle
 *   3. 根节点 style 绑定：style="flex-direction: column;{{animStyle}}"
 *   4. 所有 router.back() 之前调用 pageAnim.markBack()
 *   5. onShow() 开头调用 pageAnim.playReturn(this)
 *
 * 说明：Vela 的 @keyframes 结束后元素回落到基础态（即 100% 帧），
 * 因此动画结束后把 animStyle 清空即可，不会产生回跳；清空后 animation-name 消失，
 * 下次返回时从 '' 切到 vela-page-in-right 才能重新触发动画。
 */
import appState from './appState.js'

var ANIM_ENTER = 'animation-name: vela-page-in-left; animation-duration: 320ms; animation-timing-function: cubic-bezier(0.2, 0, 0, 1);'
var ANIM_RIGHT = 'animation-name: vela-page-in-right; animation-duration: 320ms; animation-timing-function: cubic-bezier(0.2, 0, 0, 1);'
// 动画结束后延迟清空 animStyle（略大于 320ms 动画时长）
var CLEANUP_MS = 380

export default {
  enterStyle: ANIM_ENTER,
  rightStyle: ANIM_RIGHT,

  // 在 router.back() 之前调用，标记即将返回上一层
  markBack: function() {
    appState.returnAnim = 'right'
  },

  // 在页面 onShow() 开头调用：若刚被返回（子页面 markBack 过），播放 OVER_RIGHT 进入动画
  playReturn: function(page) {
    if (appState.returnAnim !== 'right') {
      return
    }
    appState.returnAnim = ''
    page.animStyle = ANIM_RIGHT
    setTimeout(function() {
      if (page.$valid) {
        page.animStyle = ''
      }
    }, CLEANUP_MS)
  }
}
