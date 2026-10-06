<template>
  <div>
    <h2 class="page-title">医保合规审查（重复收费 / 超频次 / 无签字 / 冷链 / 目录版本）</h2>
    <div class="card-soft">
      <el-radio-group v-model="filter" size="default" class="mb12" @change="load">
        <el-radio-button label="">全部</el-radio-button>
        <el-radio-button label="VIOLATION">存疑/违规</el-radio-button>
        <el-radio-button label="COMPLIANT">合规</el-radio-button>
        <el-radio-button label="PENDING">待审查</el-radio-button>
      </el-radio-group>
      <el-table :data="records" v-loading="loading" size="small">
        <el-table-column label="账单号" width="190"><template #default="{row}">{{ row.billNo }}</template></el-table-column>
        <el-table-column label="患者" width="90"><template #default="{row}">{{ row.patientName }}</template></el-table-column>
        <el-table-column label="金额" width="90" align="right"><template #default="{row}">¥{{ row.totalAmount }}</template></el-table-column>
        <el-table-column label="自动初审" width="100" align="center">
          <template #default="{row}">
            <el-progress :percentage="row.record.autoScore" :stroke-width="14"
                         :status="row.record.autoScore>=80?'success':row.record.autoScore>=60?'':'exception'" />
          </template>
        </el-table-column>
        <el-table-column label="命中规则">
          <template #default="{row}">
            <el-tag v-for="i in row.issues.slice(0,3)" :key="i.ruleCode" size="small"
                    :type="i.severity==='VIOLATION'?'danger':i.severity==='INFO'?'success':'warning'" class="rule-tag">
              {{ i.ruleCode }}
            </el-tag>
            <el-tag v-if="row.issues.length>3" size="small">+{{ row.issues.length-3 }}</el-tag>
            <span v-if="!row.issues.length" class="muted">未命中规则</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{row}"><el-tag size="small" :type="tagType('ComplianceStatus',row.record.status)">{{ label('ComplianceStatus',row.record.status) }}</el-tag></template>
        </el-table-column>
        <el-table-column label="经办复核" width="100">
          <template #default="{row}">{{ row.record.auditor?.name || '-' }}</template>
        </el-table-column>
        <el-table-column label="操作" width="120" fixed="right">
          <template #default="{row}">
            <el-button link type="primary" size="small" @click="open(row)">审查</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <el-drawer v-model="detailVisible" size="620px" :title="'合规审查 · ' + (current?.billNo||'')">
      <template v-if="current">
        <div class="mb12">
          <el-tag size="large" :type="tagType('ComplianceStatus',current.record.status)">
            {{ label('ComplianceStatus',current.record.status) }}
          </el-tag>
          <span class="ml8">自动初审得分：<b>{{ current.record.autoScore }}</b></span>
          <span class="ml8">经办机构：{{ current.record.agencyName || '-' }}</span>
        </div>

        <div v-if="!current.issues.length" class="ok-box">未命中任何风险规则：无重复收费、无超频次、签字/照片/冷链资料齐全。</div>
        <el-alert v-for="(i,idx) in current.issues" :key="idx" class="issue"
                  :type="i.severity==='VIOLATION'?'error':i.severity==='INFO'?'success':'warning'" :closable="false" show-icon>
          <template #title>
            <div><b>{{ i.ruleCode }} · {{ ruleName[i.ruleCode] }}</b></div>
            <div>{{ i.message }}</div>
            <div class="muted" v-if="i.relatedItemNos?.length">关联单据：{{ [...new Set(i.relatedItemNos)].join('、') }}</div>
          </template>
        </el-alert>

        <el-divider />
        <div class="section-title">经办复核结论</div>
        <el-input v-model="comment" type="textarea" :rows="3" placeholder="复核意见（如：一日两换经家属签字与照片佐证，情况属实）" />
        <div class="footer-actions">
          <el-button @click="goBill">查看账单明细与证据</el-button>
          <el-button type="danger" @click="audit('VIOLATION')">判定存疑/违规（退回整改）</el-button>
          <el-button type="success" @click="audit('COMPLIANT')">复核合规通过</el-button>
        </div>
      </template>
    </el-drawer>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import api from '../api'
import { label, tagType } from '../labels'

const router = useRouter()
const records = ref([])
const loading = ref(true)
const filter = ref('VIOLATION')
const detailVisible = ref(false)
const current = ref(null)
const comment = ref('')

const ruleName = {
  R1: '重复收费', R2: '超频次限制', R3: '缺少家属签字',
  R4: '医保资格存疑', R5: '冷链断链风险', R6: '医保目录变化(按快照结算)', R7: '缺少现场照片',
  R8: '缺货替代材料(确认链+医保差异)'
}

async function load() {
  loading.value = true
  records.value = (await api.get('/compliance' + (filter.value ? '?status=' + filter.value : ''))).data
  loading.value = false
}
onMounted(load)

function open(row) {
  current.value = row
  comment.value = row.record.auditorComment || ''
  detailVisible.value = true
}
async function audit(result) {
  await api.post(`/compliance/${current.value.record.id}/audit`,
    { result, comment: comment.value, conclusion: comment.value, agencyName: '镇医保服务站' })
  ElMessage.success(result === 'COMPLIANT' ? '复核通过' : '已标记存疑并退回')
  detailVisible.value = false
  load()
}
function goBill() {
  router.push({ path: '/bills' })
}
</script>

<style scoped>
.mb12 { margin-bottom: 12px; } .ml8 { margin-left: 8px; }
.rule-tag { margin: 0 4px 4px 0; }
.issue { margin-bottom: 8px; }
.ok-box { background: #f0f9eb; border: 1px solid #c2e7b0; color: #529b2e; padding: 10px 14px; border-radius: 6px; margin-bottom: 10px; }
.section-title { font-weight: 600; margin-bottom: 8px; }
.footer-actions { margin-top: 14px; text-align: right; }
</style>
