package com.whyy.snapnotes.data

/**
 * 闪念小抄 - 内置知识点商店数据
 * 来源：原闪念小抄手环端内置知识点（10科目159条）
 * 开发者：SnapNotes | 价格：免费
 */

data class StoreSubject(
    val name: String,
    val entries: List<StoreEntry>
)

data class StoreEntry(
    val id: Int,
    val title: String,
    val desc: String
)

/**
 * 内置知识点包：10科目共159条，全部免费。
 */
val BUILTIN_STORE_ITEMS = listOf(
    StoreSubject(
        name = "语文",
        entries = listOf(
            StoreEntry(id = 1, title = "静女", desc = "《诗经》是春秋时期我国第一部诗歌总集，《静女》出自《诗经·邶风》，是一首爱情诗，描写男女青年约会的情景。"),
            StoreEntry(id = 2, title = "氓", desc = "春秋时期《诗经·卫风》中的弃妇诗，以女子口吻叙述恋爱、婚变到被弃的过程，反映了当时社会妇女的悲惨境遇。"),
            StoreEntry(id = 3, title = "无衣", desc = "春秋时期《诗经·秦风》中的军中战歌，表现秦国军民团结互助、同仇敌忾的英勇精神，是《诗经》中著名的爱国主义诗篇。"),
            StoreEntry(id = 4, title = "采薇", desc = "春秋时期《诗经·小雅·采薇》写戍卒归途中所思所感，表现士兵思乡与卫国之情，以\"),
            StoreEntry(id = 5, title = "离骚（节选）", desc = "战国时期楚国诗人屈原自叙身世、理想与遭遇的浪漫主义政治抒情长诗，是楚辞的代表作。"),
            StoreEntry(id = 6, title = "涉江采芙蓉", desc = "汉末《古诗十九首》之一，以游子采芳草欲赠远人而不得寄，抒发思乡怀人之情。"),
            StoreEntry(id = 7, title = "孔雀东南飞（并序）", desc = "汉末乐府民歌叙事长诗，写焦仲卿与刘兰芝的爱情悲剧，是古代最长的一首叙事诗。"),
            StoreEntry(id = 8, title = "短歌行", desc = "东汉末年曹操所作乐府诗，抒写求贤若渴之情与建功立业之志，是建安文学代表作。"),
            StoreEntry(id = 9, title = "归园田居（其一）", desc = "东晋陶渊明辞官归隐后所作田园诗，表达对官场束缚的厌倦与回归自然的喜悦。"),
            StoreEntry(id = 10, title = "梦游天姥吟留别", desc = "唐代李白所作浪漫主义山水诗，描写梦中游历天姥山的奇幻景象，表达了对光明理想追求的坚定与对权贵的蔑视。"),
            StoreEntry(id = 11, title = "蜀道难", desc = "唐代李白所作乐府诗，以雄奇想象和夸张笔墨描绘蜀道险峻，抒发世路艰难之感慨。"),
            StoreEntry(id = 12, title = "将进酒", desc = "唐代李白所作乐府诗，以豪放语言抒写人生短暂、怀才不遇的愤懑，以及借酒放达的乐观自信。"),
            StoreEntry(id = 13, title = "登高", desc = "唐代杜甫所作七言律诗，被誉为\"),
            StoreEntry(id = 14, title = "蜀相", desc = "唐代杜甫所作七言律诗，写诸葛亮的祠堂，抒发了对这位政治家的敬仰之情以及自己壮志未酬的感慨。"),
            StoreEntry(id = 15, title = "秋兴八首（其一）", desc = "唐代杜甫流寓夔州时所作的七律组诗，借秋天景色抒发故国之思与身世之感。（旧版收录）"),
            StoreEntry(id = 16, title = "咏怀古迹（其三）", desc = "唐代杜甫的七言律诗，借咏王昭君寄托自己怀才不遇的感慨。（旧版收录）"),
            StoreEntry(id = 17, title = "琵琶行（并序）", desc = "唐代白居易的长篇乐府诗，写琵琶女的身世和诗人的贬谪之叹，表现了\"),
            StoreEntry(id = 18, title = "春江花月夜", desc = "唐代张若虚所作的乐府旧题诗，以月照春江的夜景为背景，融诗情画意哲理于一体，被称为\"),
            StoreEntry(id = 19, title = "燕歌行（并序）", desc = "唐代高适的边塞诗代表作，写征戍之苦与将领的骄奢，讽刺了军中苦乐不均的现象。"),
            StoreEntry(id = 20, title = "李凭箜篌引", desc = "唐代李贺描写音乐的浪漫主义诗作，以瑰丽想象和夸张手法描绘李凭弹箜篌的绝妙音色。"),
            StoreEntry(id = 21, title = "锦瑟", desc = "唐代李商隐的朦胧诗代表作，以锦瑟起兴，追思华年往事，寄托身世之慨与爱情之伤。"),
            StoreEntry(id = 22, title = "马嵬（其二）", desc = "唐代李商隐的咏史诗，借唐玄宗与杨贵妃的爱情悲剧讽刺帝王贪恋女色、误国误民。（旧版收录）"),
            StoreEntry(id = 23, title = "登岳阳楼", desc = "唐代杜甫的五言律诗，写登岳阳楼所见壮阔景象，抒发身世之悲与忧国之情。"),
            StoreEntry(id = 24, title = "念奴娇·赤壁怀古", desc = "宋代苏轼的词作，借赤壁怀古抒发对古代英雄的追慕与自身壮志未酬的感慨，是豪放词代表作。"),
            StoreEntry(id = 25, title = "永遇乐·京口北固亭怀古", desc = "宋代辛弃疾的词作，借咏史抒发对国事的感慨与英雄失路的悲愤，是豪放词的代表作。"),
            StoreEntry(id = 26, title = "声声慢（寻寻觅觅）", desc = "宋代李清照晚年的代表作，以委婉细腻的笔触抒发国破家亡后的孤寂凄凉之感。"),
            StoreEntry(id = 27, title = "虞美人（春花秋月何时了）", desc = "南唐后主李煜的代表作，以春花秋月起兴，抒发亡国之痛与故国之思。"),
            StoreEntry(id = 28, title = "鹊桥仙（纤云弄巧）", desc = "宋代秦观所作的婉约词代表作，以牛郎织女传说歌颂坚贞不渝的爱情。"),
            StoreEntry(id = 29, title = "桂枝香·金陵怀古", desc = "宋代王安石所作金陵怀古词，通过对六朝旧事的感叹抒发兴亡之慨。"),
            StoreEntry(id = 30, title = "念奴娇·过洞庭", desc = "宋代张孝祥的豪放词代表作，以洞庭湖月夜景色抒发高洁人格与旷达胸襟。"),
            StoreEntry(id = 31, title = "江城子·乙卯正月二十日夜记梦", desc = "宋代苏轼的词作，以记梦的形式表达对亡妻的深切思念，是悼亡词的代表作。"),
            StoreEntry(id = 32, title = "书愤", desc = "宋代陆游的七言律诗，写诗人虽年老仍渴望报国，抒发壮志难酬的悲愤。"),
            StoreEntry(id = 33, title = "临安春雨初霁", desc = "宋代陆游的诗作，写客居临安时的感受，表达对官场生活的厌倦和对田园生活的向往。"),
            StoreEntry(id = 34, title = "望海潮（东南形胜）", desc = "宋代柳永的代表作，以铺叙手法描写杭州的繁华景象，是柳永慢词的代表作。"),
            StoreEntry(id = 35, title = "扬州慢（淮左名都）", desc = "宋代姜夔的自度曲，写扬州战后残破景象，抒发兴亡之感，是姜夔的代表作。"),
            StoreEntry(id = 36, title = "雨霖铃（寒蝉凄切）", desc = "宋代柳永的代表作，写离别时的伤感，是宋词中描写离愁别绪的名篇。（旧版收录）"),
            StoreEntry(id = 37, title = "定风波（莫听穿林打叶声）", desc = "宋代苏轼的词作，写途中遇雨却依然从容豁达，表现词人旷达的胸襟。（旧版收录）"),
            StoreEntry(id = 38, title = "水龙吟·登建康赏心亭", desc = "宋代辛弃疾的词作，写登亭所见秋景，抒发英雄失路、壮志难酬的悲愤。（旧版收录）"),
            StoreEntry(id = 39, title = "醉花阴（薄雾浓云）", desc = "宋代李清照的词作，写重阳佳节独守空闺的愁绪，是李清照前期代表作。（旧版收录）"),
            StoreEntry(id = 40, title = "窦娥冤（节选）", desc = "元代关汉卿的杂剧代表作，写窦娥被冤枉的故事，\"),
            StoreEntry(id = 41, title = "游园（【皂袍】）", desc = "明代汤显祖《牡丹亭》中的经典唱段，写杜丽娘游园时的所见所感，表现了对青春和爱情的向往。"),
            StoreEntry(id = 42, title = "沁园春·长沙", desc = "毛泽东的词作，写长沙秋景，抒发青年时代的革命豪情和壮志。（旧版收录）"),
            StoreEntry(id = 43, title = "子路、曾皙、冉有、公西华侍坐", desc = "选自《论语·先进》，记录孔子与四位弟子畅谈志向的对话。"),
            StoreEntry(id = 44, title = "齐桓晋文之事", desc = "选自《孟子·梁惠王上》，孟子与齐宣王谈论王道仁政的对话。"),
            StoreEntry(id = 45, title = "寡人之于国也", desc = "选自《孟子·梁惠王上》，孟子与梁惠王论述治国之道的对话。（旧版收录）"),
            StoreEntry(id = 46, title = "庖丁解牛", desc = "选自《庄子·养生主》，讲述庖丁解牛、寓道于技的寓言。"),
            StoreEntry(id = 47, title = "逍遥游", desc = "选自《庄子·逍遥游》，描述庄子逍遥自在、无待而游的境界。（旧版收录）"),
            StoreEntry(id = 48, title = "烛之武退秦师", desc = "选自《左传·僖公三十年》，记述烛之武说服秦伯退兵的故事。"),
            StoreEntry(id = 49, title = "荆轲刺秦王", desc = "选自《战国策·燕策三》，记述荆轲刺秦王的悲壮故事。（旧版收录）"),
            StoreEntry(id = 50, title = "劝学", desc = "选自《荀子·劝学》，论述学习的重要性和学习方法。"),
            StoreEntry(id = 51, title = "鸿门宴", desc = "选自司马迁《史记·项羽本纪》，记述刘邦、项羽在鸿门宴上的明争暗斗。"),
            StoreEntry(id = 52, title = "屈原列传", desc = "选自司马迁《史记·屈原贾生列传》，记述屈原的生平、遭遇及其文学成就。"),
            StoreEntry(id = 53, title = "苏武传", desc = "选自班固《汉书·李广苏建传》，记述苏武出使匈奴被扣留、持节不屈的壮烈事迹。"),
            StoreEntry(id = 54, title = "兰亭集序", desc = "王羲之记述兰亭修禊雅集、由乐转悲的生命感悟。"),
            StoreEntry(id = 55, title = "归去来兮辞（并序）", desc = "陶渊明自述辞官归田的心路历程与田园生活的愿景。"),
            StoreEntry(id = 56, title = "谏太宗十思疏", desc = "魏征劝谏唐太宗居安思危、戒奢以俭的政论名篇。"),
            StoreEntry(id = 57, title = "滕王阁序", desc = "王勃记述滕王阁盛会，抒发仕途穷通之慨。（旧版收录）"),
            StoreEntry(id = 58, title = "师说", desc = "韩愈论述从师求学之道的名篇，批判当时耻于从师的士大夫风气。"),
            StoreEntry(id = 59, title = "过秦论", desc = "贾谊论述秦朝兴衰成败的史论名篇，分析秦亡之失在于不施仁义。"),
            StoreEntry(id = 60, title = "游褒禅山记", desc = "王安石借游褒禅山之机，阐述治学处世须有志、力、物相副才能无悔之理。（旧版收录）"),
            StoreEntry(id = 61, title = "答司马谏议书", desc = "王安石答复司马光对其变法指责的书信，辩驳清晰，立场坚定。"),
            StoreEntry(id = 62, title = "赤壁赋", desc = "苏轼与客泛舟赤壁江面，借主客问答抒发人生感悟与旷达胸怀。"),
            StoreEntry(id = 63, title = "石钟山记", desc = "苏轼考察石钟山得名由来的游记，阐明治学须重实地调查的道理。"),
            StoreEntry(id = 64, title = "登泰山记", desc = "姚鼐记述冬日登泰山日观峰观日出全过程，详记山道、石级、雪景与日出奇观。"),
            StoreEntry(id = 65, title = "五代史伶官传序", desc = "欧阳修借后唐庄宗得失天下事，阐明「忧劳可以兴国，逸豫可以亡身」的史论名篇。"),
            StoreEntry(id = 66, title = "项脊轩志", desc = "归有光借项脊轩的兴废变迁，追述家常琐事与亲长音容，抒发深婉情思。"),
            StoreEntry(id = 67, title = "促织", desc = "蒲松龄借成名一家因促织（蟋蟀）而遭逢的起伏，揭露世态炎凉与科举弊端。"),
            StoreEntry(id = 68, title = "与妻书", desc = "林觉民写给妻子的诀别信，既有儿女情长，又有为国捐躯的浩然之气。"),
        )
    ),
    StoreSubject(
        name = "数学",
        entries = listOf(
            StoreEntry(id = 1, title = "集合与常用逻辑用语", desc = "集合运算、命题逻辑、充要条件"),
            StoreEntry(id = 2, title = "函数的概念与性质", desc = "定义域、值域、单调性、奇偶性、周期性"),
            StoreEntry(id = 3, title = "基本初等函数", desc = "指数/对数/幂函数的图像与性质"),
            StoreEntry(id = 4, title = "二次函数与不等式", desc = "二次函数图像、最值、根的分布"),
            StoreEntry(id = 5, title = "导数及其应用", desc = "导数概念、求导法则、几何意义、应用"),
            StoreEntry(id = 6, title = "三角函数", desc = "同角关系、诱导公式、和差角、倍角、正弦型函数"),
            StoreEntry(id = 7, title = "平面向量", desc = "向量运算、数量积、平行与垂直"),
            StoreEntry(id = 8, title = "数列", desc = "等差数列、等比数列、求和方法"),
            StoreEntry(id = 9, title = "不等式", desc = "基本不等式、线性规划、柯西不等式"),
            StoreEntry(id = 10, title = "立体几何", desc = "点线面关系、空间角、体积、空间向量"),
            StoreEntry(id = 11, title = "解析几何", desc = "直线/圆/圆锥曲线的方程与性质"),
            StoreEntry(id = 12, title = "概率与统计", desc = "排列组合、概率、分布列、正态分布"),
        )
    ),
    StoreSubject(
        name = "英语",
        entries = listOf(
            StoreEntry(id = 1, title = "核心词汇3500速记", desc = "高考必背3500词分类速记"),
            StoreEntry(id = 2, title = "英语时态全表", desc = "16种时态的含义、结构与标志词"),
            StoreEntry(id = 3, title = "英语三大从句", desc = "名词性从句、定语从句、状语从句"),
            StoreEntry(id = 4, title = "非谓语动词", desc = "doing/done/to do 的用法区分"),
            StoreEntry(id = 5, title = "写作高级句式", desc = "提升作文档次的金句模板"),
            StoreEntry(id = 6, title = "阅读理解技巧", desc = "主旨/细节/推理/词义题的应试策略"),
            StoreEntry(id = 7, title = "完形填空策略", desc = "上下文逻辑、复现信号、固定搭配"),
            StoreEntry(id = 8, title = "常见短语动词", desc = "高频 phrasal verbs 汇总"),
        )
    ),
    StoreSubject(
        name = "物理",
        entries = listOf(
            StoreEntry(id = 1, title = "运动学", desc = "位移/速度/加速度，匀变速直线运动规律"),
            StoreEntry(id = 2, title = "相互作用——力", desc = "重力/弹力/摩擦力/力的合成与分解"),
            StoreEntry(id = 3, title = "牛顿运动定律", desc = "牛顿三定律、超重与失重、连接体"),
            StoreEntry(id = 4, title = "曲线运动与万有引力", desc = "平抛、圆周运动、万有引力定律"),
            StoreEntry(id = 5, title = "功和能", desc = "功/功率/动能定理/机械能守恒"),
            StoreEntry(id = 6, title = "动量", desc = "动量定理、动量守恒、碰撞与爆炸"),
            StoreEntry(id = 7, title = "静电场", desc = "库仑定律、电场强度、电势、电容器"),
            StoreEntry(id = 8, title = "恒定电流", desc = "欧姆定律、串并联、电功电功率"),
            StoreEntry(id = 9, title = "磁场", desc = "安培力、洛伦兹力、带电粒子在磁场中运动"),
            StoreEntry(id = 10, title = "电磁感应", desc = "楞次定律、法拉第定律、自感互感"),
            StoreEntry(id = 11, title = "交变电流", desc = "正弦交流电、描述量、变压器"),
            StoreEntry(id = 12, title = "机械振动与机械波", desc = "简谐运动、波速波长频率关系"),
            StoreEntry(id = 13, title = "光学", desc = "几何光学反射折射、物理光学干涉衍射"),
            StoreEntry(id = 14, title = "热学", desc = "分子动理论、理想气体状态方程"),
            StoreEntry(id = 15, title = "原子物理", desc = "原子结构、核反应、波粒二象性"),
        )
    ),
    StoreSubject(
        name = "化学",
        entries = listOf(
            StoreEntry(id = 1, title = "物质的量", desc = "摩尔、气体摩尔体积、物质的量浓度"),
            StoreEntry(id = 2, title = "氧化还原反应", desc = "化合价升降、电子转移、配平"),
            StoreEntry(id = 3, title = "离子反应", desc = "电解质、离子方程式、离子共存"),
            StoreEntry(id = 4, title = "化学反应速率与平衡", desc = "速率影响因素、化学平衡、平衡常数"),
            StoreEntry(id = 5, title = "电化学", desc = "原电池、电解池、金属腐蚀与防护"),
            StoreEntry(id = 6, title = "水溶液离子平衡", desc = "弱电解质电离、盐类水解、沉淀溶解"),
            StoreEntry(id = 7, title = "原子结构与元素周期律", desc = "原子核外电子排布、周期表、元素周期律"),
            StoreEntry(id = 8, title = "化学键与分子结构", desc = "离子键/共价键/金属键、VSEPR、杂化轨道"),
            StoreEntry(id = 9, title = "有机化学基础", desc = "烃/烃衍生物/合成、同分异构体"),
            StoreEntry(id = 10, title = "化学反应原理综合", desc = "焓变/熵变/吉布斯自由能判断反应自发性"),
        )
    ),
    StoreSubject(
        name = "生物",
        entries = listOf(
            StoreEntry(id = 1, title = "细胞的基本结构", desc = "细胞膜/细胞器/细胞核的结构与功能"),
            StoreEntry(id = 2, title = "物质跨膜运输", desc = "被动运输/主动运输/胞吞胞吐"),
            StoreEntry(id = 3, title = "酶与ATP", desc = "酶的本质/特性/影响因素，ATP的结构与功能"),
            StoreEntry(id = 4, title = "光合作用", desc = "光反应/暗反应的过程、影响因素、C3/C4植物"),
            StoreEntry(id = 5, title = "细胞呼吸", desc = "有氧呼吸三阶段/无氧呼吸/细胞呼吸应用"),
            StoreEntry(id = 6, title = "细胞的生命历程", desc = "细胞分裂/分化/凋亡/衰老/癌变"),
            StoreEntry(id = 7, title = "遗传的分子基础", desc = "DNA/RNA/基因表达/中心法则"),
            StoreEntry(id = 8, title = "孟德尔遗传定律", desc = "分离定律/自由组合定律/伴性遗传"),
            StoreEntry(id = 9, title = "植物激素调节", desc = "生长素/赤霉素/细胞分裂素/脱落酸/乙烯"),
            StoreEntry(id = 10, title = "种群与群落", desc = "种群特征/群落结构/种间关系"),
            StoreEntry(id = 11, title = "生态系统", desc = "能量流动/物质循环/信息传递/稳定性"),
        )
    ),
    StoreSubject(
        name = "历史",
        entries = listOf(
            StoreEntry(id = 1, title = "中国古代政治制度", desc = "分封制/郡县制/三省六部/科举制/军机处"),
            StoreEntry(id = 2, title = "中国古代经济", desc = "农业/手工业/商业/经济政策"),
            StoreEntry(id = 3, title = "中国近现代革命", desc = "列强侵华/太平天国/戊戌变法/辛亥革命/新文化/五四"),
            StoreEntry(id = 4, title = "世界古代文明", desc = "古希腊民主/罗马法/中世纪/文艺复兴"),
            StoreEntry(id = 5, title = "资本主义发展", desc = "新航路/殖民扩张/工业革命/工人运动"),
            StoreEntry(id = 6, title = "两次世界大战", desc = "一战/二战/冷战格局/多极化趋势"),
        )
    ),
    StoreSubject(
        name = "地理",
        entries = listOf(
            StoreEntry(id = 1, title = "宇宙与地球", desc = "地球运动/太阳高度/时间计算"),
            StoreEntry(id = 2, title = "大气运动与气候", desc = "热力环流/三圈环流/气候类型"),
            StoreEntry(id = 3, title = "常见天气系统", desc = "气旋/反气旋/冷锋/暖锋/准静止锋"),
            StoreEntry(id = 4, title = "水循环与海水运动", desc = "水循环环节/海水运动/洋流"),
            StoreEntry(id = 5, title = "地表形态塑造", desc = "内力作用/外力作用/主要地貌"),
            StoreEntry(id = 6, title = "自然资源与区域发展", desc = "水资源/能源/海洋资源/区域协调"),
        )
    ),
    StoreSubject(
        name = "政治",
        entries = listOf(
            StoreEntry(id = 1, title = "公民的政治生活", desc = "公民权利/义务/政治参与"),
            StoreEntry(id = 2, title = "政府与人民", desc = "政府职能/宗旨/依法行政"),
            StoreEntry(id = 3, title = "人民代表大会制度", desc = "人大的地位/职权/人大代表"),
            StoreEntry(id = 4, title = "政党制度与民族宗教", desc = "中国共产党/民主党派/民族/宗教政策"),
            StoreEntry(id = 5, title = "当代国际社会", desc = "主权国家/国际组织/时代主题/多极化"),
            StoreEntry(id = 6, title = "文化生活", desc = "文化与社会/传承创新/文化自信"),
            StoreEntry(id = 7, title = "生活与哲学", desc = "唯物论/辩证法/认识论/历史唯物主义核心观点"),
        )
    ),
    StoreSubject(
        name = "信息技术",
        entries = listOf(
            StoreEntry(id = 1, title = "Python 简介与环境", desc = "Python 发展史、特点、安装与运行方式"),
            StoreEntry(id = 2, title = "变量与数据类型", desc = "基本类型/类型转换/命名规范"),
            StoreEntry(id = 3, title = "字符串操作", desc = "索引/切片/常用方法/格式化"),
            StoreEntry(id = 4, title = "列表 list", desc = "增删改查/排序/列表推导式"),
            StoreEntry(id = 5, title = "元组与字典", desc = "tuple不可变序列/dict键值映射"),
            StoreEntry(id = 6, title = "条件与循环", desc = "if/elif/else/for/while/break/continue"),
            StoreEntry(id = 7, title = "函数与Lambda", desc = "def定义/参数/返回值/作用域/Lambda"),
            StoreEntry(id = 8, title = "模块与文件操作", desc = "import导入/常用模块/读写文件"),
            StoreEntry(id = 9, title = "异常处理", desc = "try/except/finally/raise自定义异常"),
            StoreEntry(id = 10, title = "面向对象编程", desc = "类与对象/属性/方法/继承/多态"),
            StoreEntry(id = 11, title = "常用标准库详解", desc = "os/sys/datetime/random/math/json/pickle"),
            StoreEntry(id = 12, title = "正则表达式 re", desc = "re模块/匹配/搜索/替换/分组"),
            StoreEntry(id = 13, title = "列表推导与生成器", desc = "推导式/生成器表达式/yield/迭代器"),
            StoreEntry(id = 14, title = "装饰器与闭包", desc = "闭包原理/@装饰器语法/常见应用"),
            StoreEntry(id = 15, title = "面向对象进阶", desc = "@property/@classmethod/@staticmethod/抽象基类"),
            StoreEntry(id = 16, title = "Python 综合应用", desc = "requests爬虫初阶/pandas数据处理入门/Flask Web极简"),
        )
    ),
)
