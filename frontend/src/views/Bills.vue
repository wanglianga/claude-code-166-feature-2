<template>
  <div>
    <div class="head-line">
      <h2 class="page-title" style="margin:0">统一账单（护理+耗材+冷链，医保/自费一单清）</h2>
      <div>
        <el-select v-model="filter" placeholder="全部状态" clearable style="width:140px" @change="load">
          <el-option v-for="(v,k) in ENUMS.BillStatus" :key="k" :label="v" :value="k" />
        </el-select>
        <el-button v-if="auth.isFinance" type="primary" class="ml8" @click="openGenerate">按账期生成账单</el-button>
      </div>
    </div>

    <div class="card-soft">
      <el-table :data="bills" v-loading="loading" size="small">
        <el-table-column prop="billNo" label="账单号" width="190" />
        <el-table-column label="患者" width="100"><template #default="{row}">{{ row.patient.name }}</template></el-table-column>
        <el-table-column label="账期" width="190">
          <template #default="{row}">{{ row.periodStart }} ~ {{ row.periodEnd }}</template>
        </el-table-column>
        <el-table-column label="总额" width="90" align="right"><template #default="{row}">¥{{ row.totalAmount }}</template></el-table-column>
        <el-table-column label="医保报销" width="100" align="right">
          <template #default="{row}"><span style="color:#1f6f5c">¥{{ row.insuranceAmount }}</span></template>
        </el-table-column>
        <el-table-column label="家属自费" width="100" align="right">
          <template #default="{row}"><span style="color:#e6a23c">¥{{ row.selfPayAmount }}</span></template>
        </el-table-column>
        <el-table-column label="状态" width="110">
          <template #default="{row}"><el-tag size="small" :type="tagType('BillStatus',row.status)">{{ label('BillStatus',row.status) }}</el-tag></template>
        </el-table-column>
        <el-table-column label="操作" width="100" fixed="right">
          <template #default="{row}"><el-button link type="primary" size="small" @click="open(row)">明细</el-button></template>
        </el-table-column>
      </el-table>
    </div>

    <!-- 账单明细抽屉 -->
    <el-drawer v-model="detailVisible" size="780px" :title="'账单 ' + (detail?.bill?.billNo || '')">
      <template v-if="detail">
        <el-descriptions :column="3" border size="small" class="mb12">
          <el-descriptions-item label="患者">{{ detail.bill.patient.name }}</el-descriptions-item>
          <el-descriptions-item label="状态">
            <el-tag size="small" :type="tagType('BillStatus',detail.bill.status)">{{ label('BillStatus',detail.bill.status) }}</el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="医保批次">{{ detail.bill.insuranceSettleNo || '-' }}</el-descriptions-item>
          <el-descriptions-item label="总额"><b>¥{{ detail.bill.totalAmount }}</b></el-descriptions-item>
          <el-descriptions-item label="医保报销"><b style="color:#1f6f5c">¥{{ detail.bill.insuranceAmount }}</b></el-descriptions-item>
          <el-descriptions-item label="家属自费"><b style="color:#e6a23c">¥{{ detail.bill.selfPayAmount }}</b></el-descriptions-item>
        </el-descriptions>

        <el-table :data="detail.items" size="small" border max-height="320">
          <el-table-column label="服务日期" width="105"><template #default="{row}">{{ row.serviceDate }}</template></el-table-column>
          <el-table-column label="项目">
            <template #default="{row}">
              {{ row.itemName }}
              <el-tag size="small" effect="plain">{{ label('CatalogCategory',row.category) }}</el-tag>
              <el-tag size="small" :type="row.reimbursable?'success':'info'">{{ row.insuranceCategory || '自费' }}</el-tag>
              <el-tooltip v-if="row.note" :content="row.note" placement="top">
                <el-tag v-if="row.note.includes('缺货替代')" size="small" type="warning" class="ml6">缺货替代</el-tag>
                <el-tag v-else-if="row.note.includes('未登记冷链')" size="small" type="danger" class="ml6">冷链未登记</el-tag>
              </el-tooltip>
              <div v-if="row.note" class="row-note">{{ row.note }}</div>
            </template>
          </el-table-column>
          <el-table-column label="来源" width="130">
            <template #default="{row}">
              <div class="muted">{{ label('BillSourceType',row.sourceType) }}</div>
              <div class="muted">{{ row.sourceNo }}</div>
            </template>
          </el-table-column>
          <el-table-column prop="quantity" label="数量" width="55" align="center" />
          <el-table-column label="单价" width="70" align="right"><template #default="{row}">{{ row.unitPrice }}</template></el-table-column>
          <el-table-column label="小计" width="80" align="right"><template #default="{row}">{{ row.amount }}</template></el-table-column>
          <el-table-column label="医保" width="75" align="right"><template #default="{row}"><span style="color:#1f6f5c">{{ row.insuranceAmount }}</span></template></el-table-column>
          <el-table-column label="自费" width="75" align="right"><template #default="{row}"><span style="color:#e6a23c">{{ row.selfPayAmount }}</span></template></el-table-column>
        </el-table>
        <div class="muted mt-12">每行均可回溯到对应巡诊记录（含照片、家属签字）与耗材出库流水；价格/比例为出账时快照，目录调整不影响本单。</div>

        <!-- 合规初审结果 -->
        <el-divider content-position="left">医保合规初审</el-divider>
        <div v-if="detail.compliance">
          <el-tag :type="tagType('ComplianceStatus',detail.compliance.status)" size="large">
            {{ label('ComplianceStatus',detail.compliance.status) }} · 得分 {{ detail.compliance.autoScore }}
          </el-tag>
          <el-alert v-for="(iss,i) in complianceIssues" :key="i" class="mt-12"
                    :type="iss.severity==='VIOLATION'?'error':'warning'" :closable="false" show-icon
                    :title="iss.ruleCode + ' ' + iss.message" />
        </div>

        <!-- 质疑处理记录 -->
        <template v-if="detail.bill.disputeNote">
          <el-divider content-position="left">家属收费质疑</el-divider>
          <el-alert type="error" :closable="false" :title="'家属质疑：' + detail.bill.disputeNote" />
          <el-alert v-if="detail.bill.disputeResolution" type="success" class="mt-12" :closable="false"
                    :title="'财务答复：' + detail.bill.disputeResolution" />
        </template>

        <div class="footer-actions">
          <el-button @click="goTrace">查看本患者服务全链（申请/执行/照片/签字/医嘱）</el-button>
          <template v-if="auth.isFamily">
            <el-button type="warning" v-if="detail.bill.status==='DRAFT'" @click="doDispute">对收费有疑问</el-button>
            <el-button type="primary" v-if="detail.bill.status==='DRAFT'" @click="doConfirm">逐项核对无误，确认账单</el-button>
          </template>
          <template v-if="auth.isFinance">
            <el-button v-if="detail.bill.status==='DISPUTED'" @click="doResolve(false)">答复质疑（维持）</el-button>
            <el-button type="warning" v-if="detail.bill.status==='DISPUTED'" @click="doResolve(true)">异议成立并剔除重算</el-button>
            <el-button type="success" v-if="detail.bill.status==='CONFIRMED'||detail.bill.status==='INSURANCE_REVIEWED'" @click="doSettle">医保结算</el-button>
          </template>
        </div>
      </template>
    </el-drawer>

    <!-- 生成账单 -->
    <el-dialog v-model="generateVisible" title="按账期生成统一账单" width="460px">
      <el-form label-width="100px">
        <el-form-item label="建床申请">
          <el-select v-model="genForm.applicationId" placeholder="选择患者建床单" style="width:100%">
            <el-option v-for="a in approvedApps" :key="a.id"
                       :label="`${a.patient.name} · ${a.bedNo||a.applyNo}`" :value="a.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="账期开始"><el-date-picker v-model="genForm.periodStart" type="date" value-format="YYYY-MM-DD" style="width:100%" /></el-form-item>
        <el-form-item label="账期结束"><el-date-picker v-model="genForm.periodEnd" type="date" value-format="YYYY-MM-DD" style="width:100%" /></el-form-item>
      </el-form>
      <div class="muted">系统自动汇总账期内已完成巡诊的护理项目与耗材领用（含冷链），已出账内容不重复计入，并立即执行医保合规初审。</div>
      <template #footer><el-button @click="generateVisible=false">取消</el-button>
        <el-button type="primary" :loading="generating" @click="doGenerate">生成</el-button></template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import api from '../api'
import { useAuthStore } from '../store/auth'
import { ENUMS, label, tagType } from '../labels'

const auth = useAuthStore()
const router = useRouter()
const bills = ref([])
const loading = ref(true)
const filter = ref('')
const detailVisible = ref(false)
const detail = ref(null)
const approvedApps = ref([])

async function load() {
  loading.value = true
  bills.value = (await api.get('/bills' + (filter.value ? '?status=' + filter.value : ''))).data
  loading.value = false
}
onMounted(async () => {
  await load()
  if (auth.isFinance) approvedApps.value = (await api.get('/applications?status=APPROVED')).data
})

const complianceIssues = computed(() => {
  try { return JSON.parse(detail.value.compliance?.issuesJson || '[]') } catch { return [] }
})

async function open(row) {
  detail.value = (await api.get(`/bills/${row.id}`)).data
  detailVisible.value = true
}

async function doConfirm() {
  const { value } = await ElMessageBox.prompt('请输入确认意见（将作为知情确认留存）', '家属确认', {
    inputValue: '护理项目、耗材与费用已逐项核对，确认无误'
  }).catch(() => ({}))
  if (value === undefined) return
  await api.post(`/bills/${detail.value.bill.id}/confirm`, { note: value })
  ElMessage.success('已确认，账单进入医保复核')
  detailVisible.value = false
  load()
}

async function doDispute() {
  const { value } = await ElMessageBox.prompt('请说明质疑的收费项目与原因，财务将对照巡诊记录、照片、签字逐项核查', '收费质疑', {
    inputType: 'textarea'
  }).catch(() => ({}))
  if (!value) return
  await api.post(`/bills/${detail.value.bill.id}/dispute`, { note: value })
  ElMessage.success('质疑已提交，账单标记为争议中')
  detailVisible.value = false
  load()
}

async function doResolve(adjust) {
  const { value } = await ElMessageBox.prompt(
    adjust ? '说明剔除/调整依据（将重新计算金额）' : '答复内容（维持原账单）', '答复家属质疑',
    { inputType: 'textarea' }).catch(() => ({}))
  if (!value) return
  await api.post(`/bills/${detail.value.bill.id}/resolve-dispute`, { resolution: value, adjust })
  ElMessage.success(adjust ? '已按异议调整并重算，账单回到待确认' : '已答复，账单回到待确认')
  detailVisible.value = false
  load()
}

async function doSettle() {
  await api.post(`/bills/${detail.value.bill.id}/settle`, {})
  ElMessage.success('医保结算完成')
  detailVisible.value = false
  load()
}

function goTrace() {
  router.push({ path: '/trace', query: { applicationId: detail.value.bill.application.id } })
}

const generateVisible = ref(false)
const generating = ref(false)
const genForm = reactive({ applicationId: null, periodStart: null, periodEnd: null })
function openGenerate() {
  const today = new Date()
  const fmt = d => d.toISOString().slice(0, 10)
  Object.assign(genForm, {
    applicationId: approvedApps.value[0]?.id,
    periodStart: fmt(new Date(today.getTime() - 14 * 864e5)),
    periodEnd: fmt(today)
  })
  generateVisible.value = true
}
async function doGenerate() {
  if (!genForm.applicationId || !genForm.periodStart) return ElMessage.warning('请完整选择')
  generating.value = true
  try {
    const b = (await api.post('/bills/generate', genForm)).data
    ElMessage.success(`账单 ${b.billNo} 已生成，合规初审：${b.totalAmount ? '' : ''}`)
    generateVisible.value = false
    load()
    open(b)
  } finally { generating.value = false }
}
</script>

<style scoped>
.head-line { display: flex; justify-content: space-between; align-items: center; margin-bottom: 14px; }
.ml8 { margin-left: 8px; } .mb12 { margin-bottom: 12px; } .mt-12 { margin-top: 12px; }
.footer-actions { margin-top: 18px; text-align: right; }
.ml6 { margin-left: 6px; }
.row-note { color: #909399; font-size: 12px; line-height: 1.5; margin-top: 2px; }
</style>
