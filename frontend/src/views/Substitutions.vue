<template>
  <div>
    <div class="head-line">
      <h2 class="page-title" style="margin:0">耗材缺货替代确认</h2>
      <el-radio-group v-model="tab" size="default" @change="load">
        <el-radio-button label="subs">替代确认单</el-radio-button>
        <el-radio-button label="restock">补货任务 <el-badge v-if="urgentRestock" :value="urgentRestock" class="tab-badge" /></el-radio-button>
      </el-radio-group>
    </div>

    <!-- ===== 替代确认单列表 ===== -->
    <template v-if="tab==='subs'">
      <el-alert type="info" :closable="false" show-icon class="mb12"
                title="流程：护士发起缺货替代（展示原医嘱/替代规格/医保报销差异/可能影响）→ 医生确认 → 家属手写签字；家属同意才执行出库，家属不同意则自动生成补货任务并调整本次护理计划。执行后在后续伤口照片中持续追踪效果。" />
      <div class="card-soft">
        <div class="head-line">
          <el-radio-group v-model="statusFilter" size="small" @change="loadSubs">
            <el-radio-button label="">全部</el-radio-button>
            <el-radio-button label="PROPOSED">待医生确认</el-radio-button>
            <el-radio-button label="DOCTOR_CONFIRMED">待家属签字</el-radio-button>
            <el-radio-button label="EXECUTED">已执行/随访中</el-radio-button>
            <el-radio-button label="REJECTED_BY_FAMILY">家属不同意</el-radio-button>
          </el-radio-group>
          <el-button v-if="canPropose" type="primary" size="small" @click="openPropose()">发起缺货替代</el-button>
        </div>
        <el-table :data="subs" v-loading="loading" size="small">
          <el-table-column prop="substitutionNo" label="单号" width="140" />
          <el-table-column label="患者" width="90"><template #default="{row}">{{ row.patient.name }}</template></el-table-column>
          <el-table-column label="原医嘱耗材（缺货）" width="170">
            <template #default="{row}">
              {{ row.originalMaterial.name }}
              <div class="muted">{{ row.originalMaterial.spec }}</div>
            </template>
          </el-table-column>
          <el-table-column label="替代材料" width="170">
            <template #default="{row}">
              {{ row.substituteMaterial.name }}
              <div class="muted">{{ row.substituteMaterial.spec }}</div>
            </template>
          </el-table-column>
          <el-table-column label="医保差异" width="150">
            <template #default="{row}">
              <div>报销 <b :class="row.insuranceDiff<0?'danger-text':''">{{ row.insuranceDiff>=0?'+':'' }}{{ row.insuranceDiff }}</b></div>
              <div>自费 <b :class="row.selfPayDiff>0?'danger-text':''">{{ row.selfPayDiff>=0?'+':'' }}{{ row.selfPayDiff }}</b></div>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="115">
            <template #default="{row}"><el-tag size="small" :type="tagType('SubstituteStatus',row.status)">{{ label('SubstituteStatus',row.status) }}</el-tag></template>
          </el-table-column>
          <el-table-column label="操作" width="120" fixed="right">
            <template #default="{row}">
              <el-button link type="primary" size="small" @click="viewDetail(row)">查看/处理</el-button>
            </template>
          </el-table-column>
        </el-table>
      </div>
    </template>

    <!-- ===== 补货任务 ===== -->
    <template v-else>
      <el-alert type="warning" :closable="false" show-icon class="mb12"
                title="家属不同意替代时系统自动生成加急补货任务；到货入库后自动为等待原品的患者安排补换药上门，并闭环对应缺货异常。" />
      <div class="card-soft">
        <el-table :data="restocks" v-loading="loading" size="small">
          <el-table-column prop="taskNo" label="补货单号" width="140" />
          <el-table-column label="耗材">
            <template #default="{row}">{{ row.material.name }} <span class="muted">{{ row.material.spec }}</span>
              <el-tag v-if="row.urgent" size="small" type="danger" effect="plain">加急</el-tag></template>
          </el-table-column>
          <el-table-column prop="requiredQty" label="需求量" width="80" align="center">
            <template #default="{row}">{{ row.requiredQty }}{{ row.material.unit }}</template>
          </el-table-column>
          <el-table-column prop="sourceNote" label="来源" show-overflow-tooltip />
          <el-table-column label="状态" width="95">
            <template #default="{row}"><el-tag size="small" :type="tagType('RestockStatus',row.status)">{{ label('RestockStatus',row.status) }}</el-tag></template>
          </el-table-column>
          <el-table-column prop="makeupVisitNo" label="补换药任务" width="150" show-overflow-tooltip />
          <el-table-column label="操作" width="210" fixed="right">
            <template #default="{row}">
              <el-button v-if="canHandleRestock && row.status==='PENDING'" link type="primary" size="small" @click="orderRestock(row)">报采购</el-button>
              <el-button v-if="canHandleRestock && (row.status==='PENDING'||row.status==='ORDERED')" link type="success" size="small" @click="openArrive(row)">到货入库</el-button>
              <el-button v-if="canHandleRestock && (row.status==='PENDING'||row.status==='ORDERED')" link type="info" size="small" @click="cancelRestock(row)">取消</el-button>
            </template>
          </el-table-column>
        </el-table>
      </div>
    </template>

    <!-- ===== 发起替代 ===== -->
    <el-drawer v-model="proposeVisible" size="620px" title="发起耗材缺货替代">
      <el-form :model="pf" label-width="104px">
        <el-form-item label="上门任务">
          <el-select v-model="pf.visitId" filterable placeholder="选择本次上门巡诊" style="width:100%" @change="onVisitChange">
            <el-option v-for="v in myVisits" :key="v.id"
                       :label="`${v.visitNo} ${v.patient.name} ${v.scheduledAt?.replace('T',' ').slice(0,5)}`"
                       :value="v.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="医嘱原品">
          <el-select v-model="pf.originalMaterialId" filterable placeholder="选择库存不足的医嘱指定耗材" style="width:100%" @change="loadCandidates">
            <el-option v-for="m in materials" :key="m.id" :disabled="m.stockQty>0"
                       :label="`${m.name}（库存${m.stockQty}${m.unit}）`" :value="m.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="原医嘱内容">
          <el-input v-model="pf.originalOrderSnapshot" type="textarea" :rows="2"
                    placeholder="医嘱对敷料规格/更换频次的要求（默认按所选材料生成，可修改）" />
        </el-form-item>

        <el-divider content-position="left">替代材料候选（含医保报销差异，自动测算）</el-divider>
        <el-table :data="candidates" size="small" highlight-current-row @current-change="onCandidate"
                  v-loading="candLoading" empty-text="请先选择上门任务与医嘱原品">
          <el-table-column label="替代材料">
            <template #default="{row}">
              <el-radio :model-value="pf.substituteMaterialId" :value="row.material.id" @change="pf.substituteMaterialId=row.material.id">
                {{ row.material.name }}
                <div class="muted">{{ row.material.spec }} ｜ 库存{{ row.material.stockQty }}</div>
              </el-radio>
            </template>
          </el-table-column>
          <el-table-column label="单价" width="70" align="right"><template #default="{row}">¥{{ row.substituteUnitPrice }}</template></el-table-column>
          <el-table-column label="医保" width="70"><template #default="{row}">{{ row.substituteInsuranceCategory }}</template></el-table-column>
          <el-table-column label="报销额" width="75" align="right"><template #default="{row}">¥{{ row.substituteInsuranceAmount }}</template></el-table-column>
          <el-table-column label="自费额" width="75" align="right"><template #default="{row}">¥{{ row.substituteSelfAmount }}</template></el-table-column>
          <el-table-column label="自费差" width="78" align="right">
            <template #default="{row}"><span :class="row.selfPayDiff>0?'danger-text':'ok-text'">{{ row.selfPayDiff>=0?'+':'' }}{{ row.selfPayDiff }}</span></template>
          </el-table-column>
        </el-table>

        <el-form-item class="mt-16" label="缺货原因">
          <el-input v-model="pf.shortageReason" type="textarea" :rows="2" />
        </el-form-item>
        <el-form-item label="可能影响">
          <el-input v-model="pf.possibleImpact" type="textarea" :rows="4"
                    placeholder="选择候选后自动生成影响说明，可补充" />
        </el-form-item>
        <el-form-item label="替代数量">
          <el-input-number v-model="pf.quantity" :min="1" :max="9" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="proposeVisible=false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submitPropose">提交医生确认</el-button>
      </template>
    </el-drawer>

    <!-- ===== 替代单详情/处理 ===== -->
    <el-drawer v-model="detailVisible" size="700px" :title="'替代确认单 ' + (d?.substitutionNo || '')">
      <template v-if="d">
        <el-descriptions :column="2" border size="small">
          <el-descriptions-item label="患者">{{ d.patient.name }}</el-descriptions-item>
          <el-descriptions-item label="上门任务">{{ d.visit.visitNo }}</el-descriptions-item>
          <el-descriptions-item label="状态" :span="2">
            <el-tag size="small" :type="tagType('SubstituteStatus',d.status)">{{ label('SubstituteStatus',d.status) }}</el-tag>
          </el-descriptions-item>
        </el-descriptions>

        <div class="section-title mt-16">① 原医嘱</div>
        <div class="info-box">{{ d.originalOrderSnapshot }}</div>
        <div class="muted">缺货原因：{{ d.shortageReason }}</div>

        <div class="section-title mt-16">② 替代材料规格与医保报销差异</div>
        <el-table :data="compareRows" size="small" border>
          <el-table-column prop="k" label="对比项" width="110" />
          <el-table-column prop="o" label="医嘱原品" />
          <el-table-column prop="s" label="替代材料">
            <template #default="{row}"><span :class="row.highlight?'danger-text':''">{{ row.s }}</span></template>
          </el-table-column>
        </el-table>
        <div class="diff-line">
          医保报销额变化：<b :class="d.insuranceDiff<0?'danger-text':'ok-text'">{{ d.insuranceDiff>=0?'+':'' }}¥{{ d.insuranceDiff }}</b>
          ｜ 家属自费变化：<b :class="d.selfPayDiff>0?'danger-text':'ok-text'">{{ d.selfPayDiff>=0?'+':'' }}¥{{ d.selfPayDiff }}</b>
        </div>

        <div class="section-title mt-16">③ 可能影响</div>
        <div class="info-box warn">{{ d.possibleImpact }}</div>

        <!-- 医生确认 -->
        <template v-if="d.status==='PROPOSED'">
          <div class="section-title mt-16">④ 医生确认</div>
          <div v-if="auth.isDoctor || auth.isAdmin" class="confirm-box">
            <el-input v-model="doctorComment" type="textarea" :rows="2" placeholder="确认意见（临床适用性/过敏风险/更换频次）" class="mb8" />
            <el-button type="primary" @click="doDoctor(true)">确认替代方案</el-button>
            <el-button type="danger" plain @click="doDoctor(false)">退回（不替代）</el-button>
          </div>
          <el-empty v-else description="等待家庭医生确认" :image-size="56" />
        </template>

        <!-- 家属签字 -->
        <template v-if="d.status==='DOCTOR_CONFIRMED'">
          <div class="section-title mt-16">④ 家属知情确认（{{ d.doctor?.name }} 已确认：{{ d.doctorComment }}）</div>
          <template v-if="canFamilyDecide">
          <el-radio-group v-model="familyAgreed" class="mb8">
            <el-radio-button :value="true">同意替代并执行</el-radio-button>
            <el-radio-button :value="false">不同意，等待原品补货</el-radio-button>
          </el-radio-group>
          <template v-if="familyAgreed">
            <el-input v-model="familySignName" placeholder="签字家属姓名" style="width:240px" class="mb8" />
            <SignPad v-model="familySignPath" :sign-name="familySignName" />
            <el-input v-model="familyComment" type="textarea" :rows="2" placeholder="家属意见（可选）" class="mt-16" />
            <div class="mt-16"><el-button type="primary" @click="submitFamily(true)">确认同意并执行替代</el-button></div>
          </template>
          <template v-else>
            <el-alert type="warning" :closable="false" show-icon class="mb8"
                      title="不同意替代：系统将自动生成加急补货任务、登记缺货异常，并要求填写本次护理计划调整（原品到货后自动安排补换药）" />
            <el-input v-model="familyComment" type="textarea" :rows="2" placeholder="家属意见（可选）" class="mb8" />
            <el-input v-model="planAdjustment" type="textarea" :rows="3"
                      placeholder="本次护理计划调整，如：生理盐水冲洗+无菌纱布覆盖，隔日观察，泡沫敷料到货后补换" class="mb8" />
            <el-button type="warning" @click="submitFamily(false)">提交并生成补货任务</el-button>
          </template>
          </template>
          <el-alert v-else type="info" :closable="false" show-icon
                    title="该替代方案待家属现场知情签字，由上门护士/家属/管理者操作；您当前角色仅可查看。" />
        </template>

        <!-- 执行/拒绝结果 -->
        <template v-if="d.status==='EXECUTED'">
          <div class="section-title mt-16">④ 家属知情同意与执行</div>
          <div class="sign-box">
            <el-image :src="d.familySignPath" class="sign-img" fit="contain" />
            <div>签字人：{{ d.familySignName }} ｜ {{ d.familySignedAt?.replace('T',' ').slice(0,16) }}</div>
            <div class="muted">家属意见：{{ d.familyComment || '-' }}</div>
          </div>
          <div class="muted">已于 {{ d.executedAt?.replace('T',' ').slice(0,16) }} 领用替代材料
            {{ d.substituteMaterial.name }} x{{ d.quantity }}（出库流水 #{{ d.outTransaction?.id }}）</div>
        </template>
        <template v-if="d.status==='REJECTED_BY_FAMILY'">
          <div class="section-title mt-16">④ 家属不同意</div>
          <el-alert type="error" :closable="false" :title="'加急补货任务 ' + (d.restockTask?.taskNo || '-') + '（' + label('RestockStatus',d.restockTask?.status) + '）'"
                    description="原品到货后系统自动安排补换药上门" class="mb8" />
          <div class="info-box">本次护理计划调整：{{ d.planAdjustment }}</div>
          <div class="muted">家属意见：{{ d.familyComment || '-' }}</div>
        </template>

        <!-- 伤口效果随访 -->
        <template v-if="d.status==='EXECUTED'">
          <div class="section-title mt-16">⑤ 后续伤口照片效果追踪</div>
          <el-timeline v-if="d.followUps?.length">
            <el-timeline-item v-for="f in d.followUps" :key="f.id"
                              :timestamp="f.observedAt?.replace('T',' ').slice(0,16)" placement="top"
                              :type="tagType('WoundOutcome',f.outcome)">
              <div><el-tag size="small" :type="tagType('WoundOutcome',f.outcome)">{{ label('WoundOutcome',f.outcome) }}</el-tag>
                <span v-if="f.woundArea!=null" class="muted">创面 {{ f.woundArea }}cm²</span></div>
              <div>{{ f.observation }}</div>
              <div v-if="f.nurseNote" class="muted">备注：{{ f.nurseNote }}</div>
              <el-image v-if="f.photoPaths" v-for="(p,i) in f.photoPaths.split(',')" :key="i" :src="p"
                        :preview-src-list="f.photoPaths.split(',')" class="fu-photo" fit="cover" />
            </el-timeline-item>
          </el-timeline>
          <el-empty v-else description="尚未记录替代后随访" :image-size="56" />
          <el-button v-if="auth.isNurse || auth.isDoctor || auth.isAdmin" size="small" type="primary"
                     @click="openFollowUp">记录本次上门伤口随访</el-button>

          <div v-if="d.relatedRisks?.length" class="mt-16">
            <el-alert v-for="r in d.relatedRisks" :key="r.id" type="error" :closable="false" show-icon class="mb8"
                      :title="r.riskType + '（' + label('RiskSeverity',r.severity) + '）'"
                      :description="r.description" />
          </div>

          <template v-if="d.materialAdjustedAt">
            <div class="section-title mt-16">⑥ 医生已调整后续护理材料</div>
            <div class="info-box ok">{{ d.materialAdjustNote }}
              <div class="muted">调整医嘱 {{ d.adjustOrder?.orderNo }} ｜ {{ d.materialAdjustedAt?.replace('T',' ').slice(0,16) }}</div>
            </div>
          </template>
          <div v-else-if="hasAbnormal && (auth.isDoctor||auth.isAdmin)" class="mt-16">
            <el-button type="warning" @click="openAdjust">回看替代原因并调整后续护理材料</el-button>
          </div>
        </template>
      </template>
    </el-drawer>

    <!-- 记录随访 -->
    <el-dialog v-model="fuVisible" title="替代耗材 · 伤口效果随访" width="520px">
      <el-form :model="fu" label-width="92px">
        <el-form-item label="关联上门">
          <el-select v-model="fu.visitId" clearable placeholder="选择本次观察对应的上门任务" style="width:100%">
            <el-option v-for="v in laterVisits" :key="v.id"
                       :label="`${v.visitNo} ${v.scheduledAt?.replace('T',' ').slice(0,16)} ${label('VisitStatus',v.status)}`"
                       :value="v.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="伤口照片">
          <UploadPhotos v-model="fu.photoPaths" type="dressing" />
        </el-form-item>
        <el-form-item label="观察结论">
          <el-radio-group v-model="fu.outcome">
            <el-radio v-for="(v,k) in ENUMS.WoundOutcome" :key="k" :value="k">{{ v }}</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="创面面积"><el-input-number v-model="fu.woundArea" :min="0" :step="0.1" :precision="1" /> cm²</el-form-item>
        <el-form-item label="观察描述"><el-input v-model="fu.observation" type="textarea" :rows="3"
                                              placeholder="渗液/红肿/瘙痒/敷料在位/愈合进度" /></el-form-item>
        <el-form-item label="护士备注"><el-input v-model="fu.nurseNote" type="textarea" :rows="2" /></el-form-item>
      </el-form>
      <div class="muted">选择“愈合变慢/过敏/感染”将自动生成风险事件并回挂本替代单，医生可回看替代原因。</div>
      <template #footer><el-button @click="fuVisible=false">取消</el-button>
        <el-button type="primary" @click="submitFollowUp">提交随访</el-button></template>
    </el-dialog>

    <!-- 医生调整材料 -->
    <el-dialog v-model="adjustVisible" title="调整后续护理材料" width="480px">
      <el-input v-model="adjustNote" type="textarea" :rows="4"
                placeholder="如：停用替代敷料，改用凡士林油纱+无菌纱布，原品到货后恢复泡沫敷料" />
      <div class="muted mt-16">提交后同步生成“调整护理方案”医嘱（需另一位医生远程复核）。</div>
      <template #footer><el-button @click="adjustVisible=false">取消</el-button>
        <el-button type="warning" @click="submitAdjust">确认调整</el-button></template>
    </el-dialog>

    <!-- 到货 -->
    <el-dialog v-model="arriveVisible" :title="'补货到货：' + (currentRestock?.material?.name||'')" width="460px">
      <el-form :model="arriveForm" label-width="110px">
        <el-form-item label="到货数量"><el-input-number v-model="arriveForm.quantity" :min="1" /></el-form-item>
        <el-form-item label="批号"><el-input v-model="arriveForm.batchNo" /></el-form-item>
        <el-form-item label="效期"><el-date-picker v-model="arriveForm.expiryDate" type="date" value-format="YYYY-MM-DD" style="width:100%" /></el-form-item>
        <el-form-item v-if="currentRestock?.material?.coldChain" label="入库温度℃"><el-input-number v-model="arriveForm.coldChainTemp" :step="0.1" :precision="1" /></el-form-item>
        <el-form-item label="备注"><el-input v-model="arriveForm.note" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="arriveVisible=false">取消</el-button>
        <el-button type="success" @click="submitArrive">确认到货并安排补换药</el-button></template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import api from '../api'
import { useAuthStore } from '../store/auth'
import { ENUMS, label, tagType } from '../labels'
import SignPad from '../components/SignPad.vue'
import UploadPhotos from '../components/UploadPhotos.vue'

const route = useRoute()
const auth = useAuthStore()
const tab = ref('subs')
const loading = ref(false)

// ===== 列表 =====
const subs = ref([])
const restocks = ref([])
const statusFilter = ref('')
const urgentRestock = computed(() => restocks.value.filter(r => r.urgent && r.status !== 'ARRIVED' && r.status !== 'CANCELLED').length)

async function load() {
  loading.value = true
  try {
    if (tab.value === 'subs') await loadSubs()
    else restocks.value = (await api.get('/restock-tasks')).data
  } finally { loading.value = false }
}
async function loadSubs() {
  loading.value = true
  try {
    subs.value = (await api.get('/substitutions' + (statusFilter.value ? '?status=' + statusFilter.value : ''))).data
  } finally { loading.value = false }
}
onMounted(async () => {
  await load()
  if (route.query.substitutionId) openDetailById(route.query.substitutionId)
  else if (route.query.visitId && (auth.isNurse || auth.isAdmin)) openPropose(route.query.visitId)
})

// ===== 发起 =====
const canPropose = computed(() => auth.isNurse || auth.isAdmin)
const canFamilyDecide = computed(() => auth.isNurse || auth.isFamily || auth.isAdmin)
const proposeVisible = ref(false)
const submitting = ref(false)
const myVisits = ref([])
const materials = ref([])
const candidates = ref([])
const candLoading = ref(false)
const pf = reactive({ visitId: null, originalMaterialId: null, substituteMaterialId: null,
  quantity: 1, originalOrderSnapshot: '', shortageReason: '', possibleImpact: '' })

async function openPropose(visitId) {
  myVisits.value = (await api.get('/visits')).data.filter(v =>
    (auth.isAdmin || v.nurse?.id === auth.user.id) &&
    ['SCHEDULED', 'IN_PROGRESS', 'COMPLETED'].includes(v.status))
  materials.value = (await api.get('/materials')).data
  Object.assign(pf, { visitId: visitId ? Number(visitId) : null, originalMaterialId: null,
    substituteMaterialId: null, quantity: 1, originalOrderSnapshot: '', shortageReason: '', possibleImpact: '' })
  candidates.value = []
  if (pf.visitId) onVisitChange()
  proposeVisible.value = true
}
function onVisitChange() {
  pf.originalMaterialId = null
  pf.substituteMaterialId = null
  candidates.value = []
}
async function loadCandidates() {
  if (!pf.visitId || !pf.originalMaterialId) return
  candLoading.value = true
  try {
    candidates.value = (await api.get(`/substitutions/candidates?visitId=${pf.visitId}&originalMaterialId=${pf.originalMaterialId}`)).data
  } finally { candLoading.value = false }
}
function onCandidate(row) {
  if (!row) return
  pf.substituteMaterialId = row.material.id
  pf.possibleImpact = row.impactTemplate
  const orig = materials.value.find(m => m.id === pf.originalMaterialId)
  if (!pf.shortageReason) pf.shortageReason = `卫生院${orig?.name || '原品'}库存不足，当前库存 ${orig?.stockQty ?? 0}${orig?.unit || ''}`
  if (!pf.originalOrderSnapshot) {
    pf.originalOrderSnapshot = `本次换药医嘱要求使用 ${orig?.name}（${orig?.spec || ''}），按护理计划执行伤口换药`
  }
}
async function submitPropose() {
  if (!pf.visitId || !pf.originalMaterialId || !pf.substituteMaterialId) {
    return ElMessage.warning('请选择上门任务、医嘱原品与替代材料')
  }
  submitting.value = true
  try {
    await api.post('/substitutions', { ...pf })
    ElMessage.success('替代申请已提交，等待家庭医生确认')
    proposeVisible.value = false
    loadSubs()
  } finally { submitting.value = false }
}

// ===== 详情 =====
const detailVisible = ref(false)
const d = ref(null)
const doctorComment = ref('')
const familyAgreed = ref(true)
const familySignName = ref('')
const familySignPath = ref('')
const familyComment = ref('')
const planAdjustment = ref('')
const hasAbnormal = computed(() => (d.value?.relatedRisks?.length || 0) > 0
  || d.value?.followUps?.some(f => ['SLOWER', 'ALLERGY', 'INFECTION'].includes(f.outcome)))

async function viewDetail(row) {
  await openDetailById(row.id)
}
async function openDetailById(id) {
  d.value = (await api.get('/substitutions/' + id)).data
  // 后端返回 {substitution, followUps, relatedRisks}，把字段铺平便于模板
  const s = d.value.substitution
  Object.assign(d.value, s)
  doctorComment.value = ''
  familyAgreed.value = true
  familySignName.value = s.patient?.primaryCaregiver || ''
  familySignPath.value = ''
  familyComment.value = ''
  planAdjustment.value = ''
  detailVisible.value = true
  if (s.status === 'EXECUTED') loadLaterVisits(s)
}
const compareRows = computed(() => {
  if (!d.value) return []
  const x = d.value
  return [
    { k: '名称', o: x.originalMaterial.name, s: x.substituteMaterial.name },
    { k: '规格', o: x.originalMaterial.spec || '-', s: x.substituteMaterial.spec || '-' },
    { k: '单价', o: '¥' + x.originalUnitPrice, s: '¥' + x.substituteUnitPrice },
    { k: '医保类别', o: x.originalInsuranceCategory, s: x.substituteInsuranceCategory,
      highlight: x.originalInsuranceCategory !== x.substituteInsuranceCategory },
    { k: '报销比例', o: x.originalReimbRate ? Math.round(x.originalReimbRate * 100) + '%' : '-',
      s: x.substituteReimbRate ? Math.round(x.substituteReimbRate * 100) + '%' : '-',
      highlight: String(x.originalReimbRate) !== String(x.substituteReimbRate) },
    { k: '医保报销额', o: '¥' + x.originalInsuranceAmount, s: '¥' + x.substituteInsuranceAmount,
      highlight: x.insuranceDiff !== 0 },
    { k: '家属自费额', o: '¥' + x.originalSelfAmount, s: '¥' + x.substituteSelfAmount,
      highlight: x.selfPayDiff !== 0 }
  ]
})

async function doDoctor(approved) {
  if (approved || await ElMessageBox.confirm('确认退回该替代方案？护士需重新发起。', '提示', { type: 'warning' }).then(() => true).catch(() => false)) {
    await api.post(`/substitutions/${d.value.id}/doctor-confirm`, { approved, comment: doctorComment.value })
    ElMessage.success(approved ? '已确认，等待家属知情签字' : '已退回')
    detailVisible.value = false
    loadSubs()
  }
}
async function submitFamily(agreed) {
  if (agreed) {
    if (!familySignName.value || !familySignPath.value) return ElMessage.warning('请家属手写签字后再提交')
  } else {
    if (!planAdjustment.value) return ElMessage.warning('请填写本次护理计划调整方案')
  }
  await api.post(`/substitutions/${d.value.id}/family-decide`, {
    agreed, signName: familySignName.value, signPath: familySignPath.value,
    comment: familyComment.value, planAdjustment: planAdjustment.value
  })
  ElMessage.success(agreed ? '替代材料已执行出库' : '已生成加急补货任务并调整本次护理计划')
  detailVisible.value = false
  load()
}

// ===== 随访 =====
const fuVisible = ref(false)
const fu = reactive({ substitutionId: null, visitId: null, photoPaths: [], outcome: 'HEALING_WELL',
  woundArea: null, observation: '', nurseNote: '' })
const laterVisits = ref([])
async function loadLaterVisits(s) {
  laterVisits.value = (await api.get('/visits?patientId=' + s.patient.id)).data
}
function openFollowUp() {
  Object.assign(fu, { substitutionId: d.value.id, visitId: null, photoPaths: [], outcome: 'HEALING_WELL',
    woundArea: null, observation: '', nurseNote: '' })
  fuVisible.value = true
}
async function submitFollowUp() {
  if (!fu.photoPaths.length) return ElMessage.warning('请至少上传一张本次伤口照片')
  if (!fu.observation) return ElMessage.warning('请填写观察描述')
  await api.post('/substitutions/follow-ups', { ...fu })
  ElMessage.success(['SLOWER', 'ALLERGY', 'INFECTION'].includes(fu.outcome)
    ? '随访已记录，已自动生成风险事件供医生回看处理' : '随访已记录')
  fuVisible.value = false
  detailVisible.value = false
  loadSubs()
}

// ===== 医生调整材料 =====
const adjustVisible = ref(false)
const adjustNote = ref('')
function openAdjust() {
  adjustNote.value = ''
  adjustVisible.value = true
}
async function submitAdjust() {
  if (!adjustNote.value) return ElMessage.warning('请填写调整意见')
  await api.post(`/substitutions/${d.value.id}/adjust-material`, { note: adjustNote.value })
  ElMessage.success('已记录调整意见并生成护理方案调整医嘱')
  adjustVisible.value = false
  detailVisible.value = false
  loadSubs()
}

// ===== 补货任务 =====
const canHandleRestock = computed(() => ['ADMIN', 'FINANCE', 'NURSE'].includes(auth.role))
async function orderRestock(row) {
  const { value } = await ElMessageBox.prompt('报采购说明', '报采购', { confirmButtonText: '确认' }).catch(() => ({}))
  if (value === undefined) return
  await api.post(`/restock-tasks/${row.id}/order`, { note: value })
  ElMessage.success('已报采购')
  load()
}
const arriveVisible = ref(false)
const currentRestock = ref(null)
const arriveForm = reactive({ quantity: 1, batchNo: '', expiryDate: null, coldChainTemp: 5, note: '' })
function openArrive(row) {
  currentRestock.value = row
  Object.assign(arriveForm, { quantity: row.requiredQty, batchNo: '', expiryDate: row.material.nearestExpiry,
    coldChainTemp: 5, note: '' })
  arriveVisible.value = true
}
async function submitArrive() {
  await api.post(`/restock-tasks/${currentRestock.value.id}/arrive`, { ...arriveForm })
  ElMessage.success('已到货入库；等待原品的患者已自动安排补换药上门')
  arriveVisible.value = false
  load()
}
async function cancelRestock(row) {
  const { value } = await ElMessageBox.prompt('取消原因', '取消补货任务', { confirmButtonText: '确认取消' }).catch(() => ({}))
  if (value === undefined) return
  await api.post(`/restock-tasks/${row.id}/cancel`, { note: value })
  load()
}
</script>

<style scoped>
.head-line { display: flex; justify-content: space-between; align-items: center; margin-bottom: 14px; }
.section-title { font-weight: 600; margin-bottom: 8px; }
.mt-16 { margin-top: 16px; } .mb8 { margin-bottom: 8px; } .mb12 { margin-bottom: 12px; }
.tab-badge { margin-left: 6px; }
.danger-text { color: #f56c6c; font-weight: 700; }
.ok-text { color: #67c23a; font-weight: 700; }
.muted { color: #909399; font-size: 12px; }
.info-box { background: #f7f9fb; border: 1px solid #ebeef5; border-radius: 6px; padding: 8px 10px;
  font-size: 13px; line-height: 1.7; white-space: pre-wrap; }
.info-box.warn { background: #fdf6ec; border-color: #f5dab1; }
.info-box.ok { background: #f0f9eb; border-color: #c2e7b0; }
.diff-line { margin-top: 8px; font-size: 13px; }
.confirm-box { background: #f4faf8; border: 1px solid #d4ece6; border-radius: 6px; padding: 10px; }
.sign-box { text-align: center; margin-bottom: 8px; }
.sign-img { width: 280px; height: 100px; border: 1px solid #eee; border-radius: 6px; background: #fafafa; }
.fu-photo { width: 110px; height: 74px; border-radius: 6px; margin: 6px 8px 0 0; border: 1px solid #ddd; }
</style>
