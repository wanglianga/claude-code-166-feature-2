<template>
  <div>
    <div class="head-line">
      <h2 class="page-title" style="margin:0">耗材缺货替代确认</h2>
      <el-radio-group v-model="tab" size="default" @change="load">
        <el-radio-button label="substitutes">替代确认单</el-radio-button>
        <el-radio-button v-if="canManage" label="replenish">补货任务</el-radio-button>
        <el-radio-button v-if="canManage" label="adjustments">护理计划调整</el-radio-button>
      </el-radio-group>
    </div>

    <!-- ===== 替代确认单列表 ===== -->
    <template v-if="tab === 'substitutes'">
      <el-alert type="info" :closable="false" show-icon class="mb12"
                title="流程：护士发起（固化原医嘱/规格/医保差异/可能影响）→ 医生确认 → 家属签字同意或拒绝 → 同意则执行并在后续伤口照片中持续追踪；拒绝则自动生成补货任务并调整本次护理计划。愈合变慢/过敏自动风险预警，医生可回看替代原因并调整后续护理材料。" />
      <div class="card-soft">
        <div class="head-line">
          <el-radio-group v-model="statusFilter" size="small" @change="loadList">
            <el-radio-button label="">全部</el-radio-button>
            <el-radio-button label="PROPOSED">待医生确认</el-radio-button>
            <el-radio-button label="DOCTOR_CONFIRMED">待家属签字</el-radio-button>
            <el-radio-button label="EXECUTED">追踪中</el-radio-button>
            <el-radio-button label="DOCTOR_REVIEWED">已回看调整</el-radio-button>
            <el-radio-button label="FAMILY_REJECTED">家属拒绝</el-radio-button>
          </el-radio-group>
          <el-button v-if="auth.isNurse || auth.isAdmin" type="primary" size="small" @click="openPropose">发起缺货替代申请</el-button>
        </div>
        <el-table :data="list" v-loading="loading" size="small">
          <el-table-column prop="substituteNo" label="确认单号" width="150" />
          <el-table-column label="患者" width="90"><template #default="{row}">{{ row.patient.name }}</template></el-table-column>
          <el-table-column label="原品（缺货）">
            <template #default="{row}">
              <span class="orig-name">{{ row.originalMaterial.name }}</span>
              <span class="muted"> {{ row.originalMaterial.spec }}｜库存{{ row.originalMaterial.stockQty }}</span>
            </template>
          </el-table-column>
          <el-table-column label="替代材料">
            <template #default="{row}">
              {{ row.substituteMaterial.name }} <span class="muted">{{ row.substituteMaterial.spec }}</span>
            </template>
          </el-table-column>
          <el-table-column label="医保/自费差异" width="150" align="center">
            <template #default="{row}">
              <div :class="row.insuranceDiff > 0 ? 'diff-up' : row.insuranceDiff < 0 ? 'diff-down' : ''">
                医保 {{ fmt(row.insuranceDiff) }}
              </div>
              <div class="muted" :class="row.selfPayDiff > 0 ? 'diff-up' : row.selfPayDiff < 0 ? 'diff-down' : ''">
                自费 {{ fmt(row.selfPayDiff) }}
              </div>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="130">
            <template #default="{row}"><el-tag size="small" :type="tagType('SubstituteStatus',row.status)">{{ label('SubstituteStatus',row.status) }}</el-tag></template>
          </el-table-column>
          <el-table-column label="操作" width="90" fixed="right">
            <template #default="{row}"><el-button link type="primary" size="small" @click="openDetail(row)">查看处理</el-button></template>
          </el-table-column>
        </el-table>
      </div>
    </template>

    <!-- ===== 补货任务 ===== -->
    <template v-if="tab === 'replenish'">
      <div class="card-soft">
        <div class="section-title">耗材补货任务（家属拒绝替代/缺货预警自动生成，到货入库后闭环）</div>
        <el-table :data="replenishList" size="small">
          <el-table-column prop="taskNo" label="任务号" width="150" />
          <el-table-column label="耗材"><template #default="{row}">{{ row.material.name }} <span class="muted">{{ row.material.spec }}</span></template></el-table-column>
          <el-table-column prop="requiredQty" label="需求数" width="80" align="center" />
          <el-table-column label="患者" width="90"><template #default="{row}">{{ row.patientName || row.patient?.name || '-' }}</template></el-table-column>
          <el-table-column prop="reason" label="原因" show-overflow-tooltip />
          <el-table-column label="预计到货" width="150"><template #default="{row}">{{ row.expectedArrivalAt?.replace('T',' ').slice(0,16) || '-' }}</template></el-table-column>
          <el-table-column label="状态" width="110">
            <template #default="{row}"><el-tag size="small" :type="tagType('ReplenishStatus',row.status)">{{ label('ReplenishStatus',row.status) }}</el-tag></template>
          </el-table-column>
          <el-table-column label="操作" width="170" fixed="right">
            <template #default="{row}">
              <el-button v-if="['PENDING','ORDERED'].includes(row.status)" link type="warning" size="small" @click="openOrderReplenish(row)">下单/催货</el-button>
              <el-button v-if="row.status !== 'ARRIVED' && row.status !== 'CLOSED'" link type="success" size="small" @click="openArrive(row)">到货入库</el-button>
            </template>
          </el-table-column>
        </el-table>
      </div>
    </template>

    <!-- ===== 护理计划调整 ===== -->
    <template v-if="tab === 'adjustments'">
      <div class="card-soft">
        <div class="section-title">家属拒绝替代后的本次护理计划调整（原品到货补做，闭环不遗漏）</div>
        <el-table :data="adjustmentList" size="small">
          <el-table-column prop="adjustNo" label="调整号" width="150" />
          <el-table-column label="患者" width="90"><template #default="{row}">{{ row.patient.name }}</template></el-table-column>
          <el-table-column prop="adjustKind" label="调整类型" width="170" />
          <el-table-column prop="adjustmentContent" label="本次调整措施" show-overflow-tooltip />
          <el-table-column label="计划补做" width="150"><template #default="{row}">{{ row.expectedMakeupAt?.replace('T',' ').slice(0,16) || '-' }}</template></el-table-column>
          <el-table-column label="状态" width="110">
            <template #default="{row}"><el-tag size="small" :type="tagType('CareAdjustStatus',row.status)">{{ label('CareAdjustStatus',row.status) }}</el-tag></template>
          </el-table-column>
          <el-table-column label="操作" width="170" fixed="right">
            <template #default="{row}">
              <el-button v-if="row.status === 'PROPOSED'" link type="warning" size="small" @click="openSchedule(row)">安排补做</el-button>
              <el-button v-if="row.status === 'PROPOSED'" link type="success" size="small" @click="openCompleteAdj(row)">补做完成</el-button>
            </template>
          </el-table-column>
        </el-table>
      </div>
    </template>

    <!-- ===== 详情抽屉 ===== -->
    <el-drawer v-model="detailVisible" size="720px" :title="'缺货替代确认单 ' + (d?.substitute?.substituteNo || '')">
      <template v-if="d">
        <el-tag :type="tagType('SubstituteStatus', d.substitute.status)" size="large">{{ label('SubstituteStatus',d.substitute.status) }}</el-tag>

        <!-- 原医嘱/护理方案 -->
        <div class="section-title mt-16">① 原医嘱 / 护理方案</div>
        <el-alert type="info" :closable="false" class="mb8">
          <template #title>
            <div v-if="d.relatedOrder">
              关联医嘱 <b>{{ d.relatedOrder.orderNo }}</b>（{{ label('OrderType', d.relatedOrder.orderType) }}）
              <el-tag size="small" class="ml6" :type="tagType('OrderStatus', d.relatedOrder.status)">{{ label('OrderStatus', d.relatedOrder.status) }}</el-tag>
            </div>
            <div v-else>无单独医嘱，按护理计划/换药常规执行</div>
          </template>
          <div class="snap-text">{{ d.substitute.originalUsageSnapshot }}</div>
          <div v-if="d.relatedOrder?.diagnosisBasis" class="muted">诊断依据：{{ d.relatedOrder.diagnosisBasis }}</div>
        </el-alert>
        <div v-if="d.substitute.shortageNote" class="muted mb12">缺货情况：{{ d.substitute.shortageNote }}</div>

        <!-- 规格与医保差异 -->
        <div class="section-title">② 替代材料规格与医保报销差异（申请时固化，事后按快照还原）</div>
        <el-table :data="compareRows" size="small" border>
          <el-table-column prop="k" label="对比项" width="120" />
          <el-table-column label="原医嘱材料"><template #default="{row}">
            <b>{{ row.o }}</b>
          </template></el-table-column>
          <el-table-column label="替代材料"><template #default="{row}">
            <b :class="row.highlight ? 'diff-warn' : ''">{{ row.n }}</b>
            <el-tag v-if="row.tag" size="small" :type="row.tag" effect="plain" class="ml6">{{ row.tagText }}</el-tag>
          </template></el-table-column>
        </el-table>
        <el-row :gutter="10" class="mt-12">
          <el-col :span="8"><div class="cost-box"><div class="muted">总费用差额</div><b :class="diffClass(d.substitute.totalDiff)">{{ fmt(d.substitute.totalDiff) }} 元</b></div></el-col>
          <el-col :span="8"><div class="cost-box"><div class="muted">医保报销差额</div><b :class="diffClass(d.substitute.insuranceDiff)">{{ fmt(d.substitute.insuranceDiff) }} 元</b></div></el-col>
          <el-col :span="8"><div class="cost-box"><div class="muted">家属自费差额</div><b :class="diffClass(d.substitute.selfPayDiff)">{{ fmt(d.substitute.selfPayDiff) }} 元</b></div></el-col>
        </el-row>
        <div class="muted mt8">负数为减少/更省钱；替代材料若为丙类或报销比例更低，医保报销下降、自费上升，须家属知情签字。</div>

        <!-- 可能影响 -->
        <div class="section-title mt-16">③ 可能影响告知</div>
        <el-alert type="warning" :closable="false"><div class="snap-text">{{ d.substitute.possibleImpact || '—' }}</div></el-alert>

        <!-- 医生确认 -->
        <div class="section-title mt-16">④ 医生确认</div>
        <div v-if="d.substitute.doctorConfirmedAt" class="confirm-box">
          <el-tag size="small" :type="d.substitute.doctorApproved ? 'success' : 'danger'">
            {{ d.substitute.doctorApproved ? '医生同意替代' : '医生不予替代' }}
          </el-tag>
          <span class="ml8">{{ d.substitute.doctor?.name }} ｜ {{ d.substitute.doctorConfirmedAt?.replace('T',' ').slice(0,16) }}</span>
          <div class="muted mt8">{{ d.substitute.doctorComment }}</div>
        </div>
        <div v-else class="action-row">
          <el-button type="primary" :disabled="!auth.isDoctor && !auth.isAdmin" @click="openDoctorConfirm(true)">确认同意替代</el-button>
          <el-button type="danger" plain :disabled="!auth.isDoctor && !auth.isAdmin" @click="openDoctorConfirm(false)">不予替代</el-button>
          <span v-if="!auth.isDoctor && !auth.isAdmin" class="muted">等待家庭医生确认</span>
        </div>

        <!-- 家属签字 -->
        <template v-if="d.substitute.doctorApproved">
          <div class="section-title mt-16">⑤ 家属知情签字</div>
          <div v-if="d.substitute.familySignedAt" class="confirm-box">
            <el-tag size="small" :type="d.substitute.status === 'FAMILY_REJECTED' ? 'info' : 'success'">
              {{ d.substitute.status === 'FAMILY_REJECTED' ? '家属不同意替代' : '家属同意替代' }}
            </el-tag>
            <span class="ml8">{{ d.substitute.familySignName }} ｜ {{ d.substitute.familySignedAt?.replace('T',' ').slice(0,16) }}</span>
            <el-image :src="d.substitute.familySignPath" class="sign-thumb ml8" fit="contain"
                      :preview-src-list="[d.substitute.familySignPath]" />
            <div class="muted mt8">意见：{{ d.substitute.familyDecisionNote || '—' }}</div>
          </div>
          <div v-else class="action-row">
            <el-button type="success" :disabled="!canFamilySign" @click="openFamilySign(true)">同意替代，签字确认</el-button>
            <el-button type="danger" plain :disabled="!canFamilySign" @click="openFamilySign(false)">不同意替代，签字说明</el-button>
            <span v-if="!canFamilySign" class="muted">等待家属账号签字（同意后执行；不同意将生成补货任务并调整本次护理计划）</span>
          </div>
        </template>

        <!-- 执行 -->
        <template v-if="d.substitute.status === 'FAMILY_AGREED'">
          <div class="section-title mt-16">⑥ 护士执行替代材料</div>
          <div class="action-row">
            <el-button type="primary" :disabled="!auth.isNurse && !auth.isAdmin" @click="openExecute">领用替代品并执行（出库记账）</el-button>
            <span v-if="!auth.isNurse && !auth.isAdmin" class="muted">家属已同意，等待责任护士上门执行</span>
          </div>
        </template>
        <template v-if="d.substitute.executedAt">
          <div class="section-title mt-16">替代执行记录</div>
          <div class="confirm-box">
            {{ d.substitute.executedBy?.name }} 于 {{ d.substitute.executedAt?.replace('T',' ').slice(0,16) }}
            领用 {{ d.substitute.substituteMaterial.name }} ×{{ d.substitute.quantity }}
            <span v-if="d.substitute.batchNo">（批号 {{ d.substitute.batchNo }}）</span>
            <div class="muted mt8">{{ d.substitute.executionNote }}</div>
          </div>
        </template>

        <!-- 拒绝分支：补货+计划调整 -->
        <template v-if="d.replenishTask">
          <div class="section-title mt-16">⑦ 家属拒绝后的联动处理</div>
          <el-alert type="warning" :closable="false" class="mb8" :title="'补货任务 ' + d.replenishTask.taskNo + '（' + label('ReplenishStatus', d.replenishTask.status) + '）'">
            <div>原品 {{ d.replenishTask.material.name }} ×{{ d.replenishTask.requiredQty }}，预计到货 {{ d.replenishTask.expectedArrivalAt?.replace('T',' ').slice(0,16) || '待下单' }}</div>
            <div class="muted">{{ d.replenishTask.reason }}</div>
          </el-alert>
          <el-alert type="info" :closable="false" :title="'本次护理计划调整 ' + d.careAdjustment?.adjustNo">
            <div class="snap-text">{{ d.careAdjustment?.adjustmentContent }}</div>
            <div>计划补做：{{ d.careAdjustment?.expectedMakeupAt?.replace('T',' ').slice(0,16) }}（{{ label('CareAdjustStatus', d.careAdjustment?.status) }}）</div>
          </el-alert>
        </template>

        <!-- 效果追踪 -->
        <template v-if="d.trackings?.length || ['EXECUTED','DOCTOR_REVIEWED'].includes(d.substitute.status)">
          <div class="section-title mt-16">⑧ 后续伤口照片效果追踪
            <el-tag v-if="d.substitute.latestOutcome" size="small" class="ml6"
                    :type="tagType('SubstituteFollowUp', d.substitute.latestOutcome)">
              最近：{{ label('SubstituteFollowUp', d.substitute.latestOutcome) }}
            </el-tag>
          </div>
          <el-timeline>
            <el-timeline-item v-for="t in d.trackings" :key="t.id" :timestamp="t.recordedAt?.replace('T',' ').slice(0,16)"
                              :type="t.outcome === 'NORMAL' ? 'success' : t.outcome === 'SLOW' ? 'warning' : 'danger'">
              <el-card shadow="never">
                <el-tag size="small" :type="tagType('SubstituteFollowUp', t.outcome)">{{ label('SubstituteFollowUp', t.outcome) }}</el-tag>
                <span class="muted ml8">{{ t.recorder?.name }} 登记</span>
                <div class="mt8">{{ t.note }}</div>
                <div v-if="t.visit" class="muted">关联巡诊：{{ t.visit.visitNo }}</div>
              </el-card>
            </el-timeline-item>
          </el-timeline>
          <div v-if="d.photos?.length" class="photo-grid">
            <el-image v-for="p in d.photos" :key="p.id" :src="p.filePath"
                      :preview-src-list="d.photos.map(x => x.filePath)" class="track-photo" fit="cover" />
          </div>
          <el-button v-if="['EXECUTED','DOCTOR_REVIEWED'].includes(d.substitute.status) && (auth.isNurse || auth.isDoctor || auth.isAdmin)"
                     class="mt-12" type="warning" plain @click="openTracking">登记本次上门伤口追踪（含照片）</el-button>
        </template>

        <!-- 医生回看调整 -->
        <template v-if="d.substitute.status === 'EXECUTED'">
          <div class="section-title mt-16">⑨ 医生回看替代原因并调整后续护理材料</div>
          <el-button type="danger" plain :disabled="!auth.isDoctor && !auth.isAdmin" @click="openReview">医生回看 · 调整后续护理材料</el-button>
          <span v-if="!auth.isDoctor && !auth.isAdmin" class="muted ml8">如愈合变慢或出现过敏，医生可回看替代原因并下达护理方案调整医嘱</span>
        </template>
        <template v-if="d.substitute.reviewedAt">
          <div class="section-title mt-16">医生回看结论</div>
          <el-alert type="error" :closable="false">
            <div>{{ d.substitute.reviewDoctor?.name }} ｜ {{ d.substitute.reviewedAt?.replace('T',' ').slice(0,16) }}</div>
            <div class="mt8">回看结论：{{ d.substitute.reviewComment }}</div>
            <div class="mt8">后续护理材料调整：{{ d.substitute.reviewAction }}</div>
            <div v-if="d.reviewOrder" class="mt8">
              已生成调整医嘱 <b>{{ d.reviewOrder.orderNo }}</b>：{{ d.reviewOrder.content }}
              <el-tag size="small" class="ml6" :type="tagType('OrderStatus', d.reviewOrder.status)">{{ label('OrderStatus', d.reviewOrder.status) }}</el-tag>
            </div>
          </el-alert>
        </template>
      </template>
    </el-drawer>

    <!-- 发起替代 -->
    <el-dialog v-model="proposeVisible" title="护士发起缺货替代申请" width="620px">
      <el-form :model="pform" label-width="110px">
        <el-form-item label="上门任务">
          <el-select v-model="pform.visitId" filterable placeholder="选择本次巡诊任务" style="width:100%">
            <el-option v-for="v in activeVisits" :key="v.id"
                       :label="`${v.visitNo} ${v.patient.name} ${v.scheduledAt?.slice(0,16)} ${label('VisitStatus', v.status)}`" :value="v.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="原医嘱材料">
          <el-select v-model="pform.originalMaterialId" placeholder="选择库存不足的原品" style="width:100%">
            <el-option v-for="m in lowMaterials" :key="m.id"
                       :label="`${m.name} ${m.spec||''}（库存${m.stockQty}${m.unit}）`" :value="m.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="替代材料">
          <el-select v-model="pform.substituteMaterialId" placeholder="选择有库存的替代品" style="width:100%">
            <el-option v-for="m in inStockMaterials.filter(x => x.id !== pform.originalMaterialId)" :key="m.id"
                       :label="`${m.name} ${m.spec||''}（库存${m.stockQty}${m.unit}）`" :value="m.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="数量"><el-input-number v-model="pform.quantity" :min="1" :max="9" /></el-form-item>
        <el-form-item label="缺货情况"><el-input v-model="pform.shortageNote" type="textarea" :rows="2" placeholder="库存/配送延误情况" /></el-form-item>
        <el-form-item label="可能影响"><el-input v-model="pform.possibleImpact" type="textarea" :rows="3"
                                                placeholder="愈合效果差异、过敏风险、使用注意、报销差异提示" /></el-form-item>
      </el-form>
      <div class="muted">提交后平台自动固化原医嘱、两种材料规格与医保报销差额，进入医生确认环节。</div>
      <template #footer><el-button @click="proposeVisible=false">取消</el-button>
        <el-button type="primary" @click="submitPropose">提交替代申请</el-button></template>
    </el-dialog>

    <!-- 医生确认 -->
    <el-dialog v-model="doctorVisible" :title="doctorApproved ? '医生确认同意替代' : '医生不予替代'" width="480px">
      <el-input v-model="doctorComment" type="textarea" :rows="3" :placeholder="doctorApproved ? '确认意见/使用注意事项' : '不予替代的原因（将通知护士与家属）'" />
      <template #footer><el-button @click="doctorVisible=false">取消</el-button>
        <el-button :type="doctorApproved ? 'primary' : 'danger'" @click="submitDoctorConfirm">确认提交</el-button></template>
    </el-dialog>

    <!-- 家属签字 -->
    <el-dialog v-model="familyVisible" :title="familyAgreed ? '家属签字：同意替代材料' : '家属签字：不同意替代'" width="520px">
      <el-alert :type="familyAgreed ? 'success' : 'warning'" :closable="false" class="mb12"
                :title="familyAgreed ? '签字后护士将使用替代材料执行，费用按替代材料目录结算。' : '不同意将不执行替代：系统自动生成原品补货任务，并调整本次护理计划（临时护理+到货补做）。'" />
      <el-form label-width="92px">
        <el-form-item :label="familyAgreed ? '补充意见' : '拒绝原因'">
          <el-input v-model="familyNote" type="textarea" :rows="2" />
        </el-form-item>
        <el-form-item label="家属姓名"><el-input v-model="familySignName" style="width:200px" /></el-form-item>
        <el-form-item label="手写签名"><SignPad v-model="familySignPath" :sign-name="familySignName" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="familyVisible=false">取消</el-button>
        <el-button :type="familyAgreed ? 'success' : 'danger'" @click="submitFamilyDecide">签名确认</el-button></template>
    </el-dialog>

    <!-- 执行 -->
    <el-dialog v-model="executeVisible" title="执行替代材料（替代出库记账）" width="520px">
      <el-form label-width="92px">
        <el-form-item label="替代品批号"><el-input v-model="execForm.batchNo" /></el-form-item>
        <el-form-item label="执行说明"><el-input v-model="execForm.note" type="textarea" :rows="2" /></el-form-item>
        <el-form-item label="伤口照片"><UploadPhotos v-model="execForm.photoPaths" type="substitute" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="executeVisible=false">取消</el-button>
        <el-button type="primary" @click="submitExecute">确认执行并出库</el-button></template>
    </el-dialog>

    <!-- 追踪 -->
    <el-dialog v-model="trackingVisible" title="后续上门伤口效果追踪" width="560px">
      <el-form label-width="92px">
        <el-form-item label="效果判定">
          <el-radio-group v-model="trackForm.outcome">
            <el-radio-button label="NORMAL">愈合正常</el-radio-button>
            <el-radio-button label="SLOW">愈合变慢</el-radio-button>
            <el-radio-button label="ALLERGY">出现过敏</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="伤口情况"><el-input v-model="trackForm.note" type="textarea" :rows="3" placeholder="创面大小/渗出/周缘皮肤/瘙痒红斑等" /></el-form-item>
        <el-form-item label="伤口照片"><UploadPhotos v-model="trackForm.photoPaths" type="substitute" /></el-form-item>
      </el-form>
      <el-alert v-if="trackForm.outcome !== 'NORMAL'" type="error" :closable="false" class="mb8"
                title="愈合变慢/过敏将自动生成风险事件，并提示医生回看替代原因、调整后续护理材料。" />
      <template #footer><el-button @click="trackingVisible=false">取消</el-button>
        <el-button type="warning" @click="submitTracking">提交追踪</el-button></template>
    </el-dialog>

    <!-- 医生回看 -->
    <el-dialog v-model="reviewVisible" title="医生回看替代原因 · 调整后续护理材料" width="600px">
      <el-form label-width="110px">
        <el-form-item label="回看结论"><el-input v-model="reviewForm.comment" type="textarea" :rows="2" placeholder="替代原因是否成立、知情链是否完整、当前伤口判断" /></el-form-item>
        <el-form-item label="材料调整措施">
          <el-input v-model="reviewForm.action" type="textarea" :rows="3" placeholder="如：停用替代敷料，无菌纱布+油纱过渡；原品到货换回；加密随访；纳入过敏名单" />
        </el-form-item>
        <el-form-item label="生成调整医嘱"><el-switch v-model="reviewForm.createOrder" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="reviewVisible=false">取消</el-button>
        <el-button type="danger" @click="submitReview">确认回看并调整</el-button></template>
    </el-dialog>

    <!-- 补货下单 -->
    <el-dialog v-model="orderReplVisible" title="补货下单/催货" width="440px">
      <el-form label-width="100px">
        <el-form-item label="下单说明"><el-input v-model="replForm.note" type="textarea" :rows="2" /></el-form-item>
        <el-form-item label="预计到货"><el-date-picker v-model="replForm.eta" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss" style="width:100%" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="orderReplVisible=false">取消</el-button>
        <el-button type="warning" @click="submitOrderRepl">确认下单</el-button></template>
    </el-dialog>

    <!-- 到货入库 -->
    <el-dialog v-model="arriveVisible" title="补货到货入库" width="460px">
      <el-form label-width="100px">
        <el-form-item label="到货数量"><el-input-number v-model="arrForm.quantity" :min="1" /></el-form-item>
        <el-form-item label="批号"><el-input v-model="arrForm.batchNo" /></el-form-item>
        <el-form-item label="效期"><el-date-picker v-model="arrForm.expiryDate" type="date" value-format="YYYY-MM-DD" style="width:100%" /></el-form-item>
        <el-form-item label="备注"><el-input v-model="arrForm.note" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="arriveVisible=false">取消</el-button>
        <el-button type="success" @click="submitArrive">确认入库</el-button></template>
    </el-dialog>

    <!-- 安排补做 -->
    <el-dialog v-model="scheduleVisible" title="安排原品到货后补做" width="440px">
      <el-form label-width="100px">
        <el-form-item label="补做时间"><el-date-picker v-model="schedForm.at" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss" style="width:100%" /></el-form-item>
        <el-form-item label="调整措施"><el-input v-model="schedForm.content" type="textarea" :rows="3" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="scheduleVisible=false">取消</el-button>
        <el-button type="warning" @click="submitSchedule">确认安排</el-button></template>
    </el-dialog>

    <!-- 补做完成 -->
    <el-dialog v-model="completeAdjVisible" title="补做完成登记" width="480px">
      <el-form label-width="100px">
        <el-form-item label="完成说明"><el-input v-model="completeAdjForm.note" type="textarea" :rows="3" placeholder="原品使用情况、伤口评估、患者反应" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="completeAdjVisible=false">取消</el-button>
        <el-button type="success" @click="submitCompleteAdj">确认补做完成（联动闭环补货任务）</el-button></template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import api from '../api'
import { useAuthStore } from '../store/auth'
import { label, tagType } from '../labels'
import SignPad from '../components/SignPad.vue'
import UploadPhotos from '../components/UploadPhotos.vue'

const auth = useAuthStore()
const route = useRoute()
const tab = ref('substitutes')
const statusFilter = ref('')
const loading = ref(false)
const list = ref([])
const detailVisible = ref(false)
const d = ref(null)
const canManage = computed(() => ['ADMIN', 'NURSE', 'FAMILY_DOCTOR'].includes(auth.role))
const canFamilySign = computed(() => auth.isFamily || auth.isAdmin)

function fmt(v) {
  if (v === null || v === undefined) return '-'
  const n = Number(v)
  return (n > 0 ? '+' : '') + n.toFixed(2)
}
function diffClass(v) {
  return v > 0 ? 'diff-up' : v < 0 ? 'diff-down' : ''
}

async function load() {
  if (tab.value === 'substitutes') await loadList()
  else if (tab.value === 'replenish') replenishList.value = (await api.get('/substitutes/replenish/list')).data
  else adjustmentList.value = (await api.get('/substitutes/adjustments/list')).data
}
async function loadList() {
  loading.value = true
  list.value = (await api.get('/substitutes' + (statusFilter.value ? '?status=' + statusFilter.value : ''))).data
  loading.value = false
}
onMounted(async () => {
  await load()
  if (route.query.id) openDetail({ id: Number(route.query.id) })
})

// ===== 详情 =====
async function openDetail(row) {
  d.value = (await api.get('/substitutes/' + row.id)).data
  detailVisible.value = true
}
const compareRows = computed(() => {
  if (!d.value) return []
  const s = d.value.substitute
  return [
    { k: '材料名称', o: s.originalMaterial.name, n: s.substituteMaterial.name },
    { k: '规格', o: s.originalMaterial.spec || '-', n: s.substituteMaterial.spec || '-' },
    { k: '单位', o: s.originalMaterial.unit, n: s.substituteMaterial.unit },
    { k: '单价', o: '¥' + s.origUnitPrice, n: '¥' + s.subUnitPrice },
    { k: '医保目录', o: s.origInsuranceCategory, n: s.subInsuranceCategory,
      highlight: s.origInsuranceCategory !== s.subInsuranceCategory,
      tag: s.origInsuranceCategory !== s.subInsuranceCategory ? 'danger' : '',
      tagText: '类别不同' },
    { k: '可报销', o: s.origReimbursable ? '是' : '否（自费）', n: s.subReimbursable ? '是' : '否（全自费）',
      highlight: s.origReimbursable !== s.subReimbursable,
      tag: s.origReimbursable !== s.subReimbursable ? 'danger' : '', tagText: '报销变化' },
    { k: '报销比例', o: (Number(s.origReimbursementRate || 0) * 100) + '%',
      n: (Number(s.subReimbursementRate || 0) * 100) + '%',
      highlight: Number(s.origReimbursementRate || 0) !== Number(s.subReimbursementRate || 0),
      tag: Number(s.origReimbursementRate || 0) !== Number(s.subReimbursementRate || 0) ? 'warning' : '', tagText: '比例不同' },
    { k: '总费用', o: '¥' + s.origTotalAmount, n: '¥' + s.subTotalAmount },
    { k: '医保报销', o: '¥' + s.origInsuranceAmount, n: '¥' + s.subInsuranceAmount },
    { k: '家属自费', o: '¥' + s.origSelfPayAmount, n: '¥' + s.subSelfPayAmount }
  ]
})

// ===== 发起 =====
const proposeVisible = ref(false)
const pform = reactive({ visitId: null, originalMaterialId: null, substituteMaterialId: null, quantity: 1, shortageNote: '', possibleImpact: '' })
const activeVisits = ref([])
const materialsAll = ref([])
const lowMaterials = computed(() => materialsAll.value.filter(m => m.stockQty <= (m.safetyStock || 0)))
const inStockMaterials = computed(() => materialsAll.value.filter(m => m.stockQty > 0))
async function openPropose() {
  activeVisits.value = (await api.get('/visits')).data.filter(v => ['SCHEDULED', 'IN_PROGRESS', 'EXCEPTION'].includes(v.status))
  materialsAll.value = (await api.get('/materials')).data
  Object.assign(pform, { visitId: activeVisits.value[0]?.id || null, originalMaterialId: null, substituteMaterialId: null, quantity: 1, shortageNote: '', possibleImpact: '' })
  proposeVisible.value = true
}
async function submitPropose() {
  if (!pform.visitId || !pform.originalMaterialId || !pform.substituteMaterialId) return ElMessage.warning('请选择巡诊任务、原品与替代品')
  await api.post('/substitutes', pform)
  ElMessage.success('替代申请已提交，等待医生确认')
  proposeVisible.value = false
  loadList()
}

// ===== 医生确认 =====
const doctorVisible = ref(false)
const doctorApproved = ref(true)
const doctorComment = ref('')
function openDoctorConfirm(approved) {
  doctorApproved.value = approved
  doctorComment.value = ''
  doctorVisible.value = true
}
async function submitDoctorConfirm() {
  await api.post(`/substitutes/${d.value.substitute.id}/doctor-confirm`, { approved: doctorApproved.value, comment: doctorComment.value })
  ElMessage.success('医生确认已提交')
  doctorVisible.value = false
  await openDetail({ id: d.value.substitute.id })
  loadList()
}

// ===== 家属签字 =====
const familyVisible = ref(false)
const familyAgreed = ref(true)
const familyNote = ref('')
const familySignName = ref('')
const familySignPath = ref('')
function openFamilySign(agreed) {
  familyAgreed.value = agreed
  familyNote.value = ''
  familySignName.value = d.value.substitute.patient.primaryCaregiver || ''
  familySignPath.value = ''
  familyVisible.value = true
}
async function submitFamilyDecide() {
  if (!familySignName.value || !familySignPath.value) return ElMessage.warning('请家属手写签名后确认')
  await api.post(`/substitutes/${d.value.substitute.id}/family-decide`, {
    agreed: familyAgreed.value, signName: familySignName.value, signPath: familySignPath.value, note: familyNote.value
  })
  ElMessage.success(familyAgreed.value ? '家属已同意，等待护士执行' : '家属不同意：已生成补货任务并调整本次护理计划')
  familyVisible.value = false
  await openDetail({ id: d.value.substitute.id })
  loadList()
}

// ===== 执行 =====
const executeVisible = ref(false)
const execForm = reactive({ batchNo: '', note: '', photoPaths: [] })
function openExecute() {
  Object.assign(execForm, { batchNo: '', note: '', photoPaths: [] })
  executeVisible.value = true
}
async function submitExecute() {
  await api.post(`/substitutes/${d.value.substitute.id}/execute`, execForm)
  ElMessage.success('替代材料已执行出库')
  executeVisible.value = false
  await openDetail({ id: d.value.substitute.id })
  loadList()
}

// ===== 追踪 =====
const trackingVisible = ref(false)
const trackForm = reactive({ outcome: 'NORMAL', note: '', photoPaths: [] })
function openTracking() {
  Object.assign(trackForm, { outcome: 'NORMAL', note: '', photoPaths: [] })
  trackingVisible.value = true
}
async function submitTracking() {
  if (!trackForm.note) return ElMessage.warning('请填写伤口情况')
  await api.post(`/substitutes/${d.value.substitute.id}/tracking`, { ...trackForm, visitId: d.value.substitute.visit?.id || null })
  ElMessage.success('追踪已记录' + (trackForm.outcome !== 'NORMAL' ? '，已生成风险事件提示医生' : ''))
  trackingVisible.value = false
  await openDetail({ id: d.value.substitute.id })
}

// ===== 医生回看 =====
const reviewVisible = ref(false)
const reviewForm = reactive({ comment: '', action: '', createOrder: true })
function openReview() {
  Object.assign(reviewForm, { comment: '', action: '', createOrder: true })
  reviewVisible.value = true
}
async function submitReview() {
  if (!reviewForm.comment || !reviewForm.action) return ElMessage.warning('请填写回看结论与材料调整措施')
  await api.post(`/substitutes/${d.value.substitute.id}/doctor-review`, reviewForm)
  ElMessage.success('已回看并调整后续护理材料')
  reviewVisible.value = false
  await openDetail({ id: d.value.substitute.id })
  loadList()
}

// ===== 补货任务 =====
const replenishList = ref([])
const orderReplVisible = ref(false)
const arriveVisible = ref(false)
const currentTask = ref(null)
const replForm = reactive({ note: '', eta: '' })
const arrForm = reactive({ quantity: 1, batchNo: '', expiryDate: '', note: '' })
function openOrderReplenish(row) {
  currentTask.value = row
  replForm.note = row.orderNote || ''
  replForm.eta = row.expectedArrivalAt || ''
  orderReplVisible.value = true
}
async function submitOrderRepl() {
  await api.post(`/substitutes/replenish/${currentTask.value.id}/order`, { note: replForm.note, expectedArrivalAt: replForm.eta })
  ElMessage.success('已登记下单/催货')
  orderReplVisible.value = false
  load()
}
function openArrive(row) {
  currentTask.value = row
  Object.assign(arrForm, { quantity: row.requiredQty, batchNo: '', expiryDate: '', note: '' })
  arriveVisible.value = true
}
async function submitArrive() {
  await api.post(`/substitutes/replenish/${currentTask.value.id}/arrive`, arrForm)
  ElMessage.success('到货已入库，库存已增加')
  arriveVisible.value = false
  load()
}

// ===== 护理调整 =====
const adjustmentList = ref([])
const scheduleVisible = ref(false)
const completeAdjVisible = ref(false)
const currentAdj = ref(null)
const schedForm = reactive({ at: '', content: '' })
const completeAdjForm = reactive({ note: '' })
function openSchedule(row) {
  currentAdj.value = row
  schedForm.at = row.expectedMakeupAt || ''
  schedForm.content = row.adjustmentContent || ''
  scheduleVisible.value = true
}
async function submitSchedule() {
  await api.post(`/substitutes/adjustments/${currentAdj.value.id}/schedule`, { expectedMakeupAt: schedForm.at, content: schedForm.content })
  ElMessage.success('补做安排已保存')
  scheduleVisible.value = false
  load()
}
function openCompleteAdj(row) {
  currentAdj.value = row
  completeAdjForm.note = ''
  completeAdjVisible.value = true
}
async function submitCompleteAdj() {
  if (!completeAdjForm.note) return ElMessage.warning('请填写补做完成说明')
  await api.post(`/substitutes/adjustments/${currentAdj.value.id}/complete`, completeAdjForm)
  ElMessage.success('补做已完成，补货任务同步闭环')
  completeAdjVisible.value = false
  load()
}
</script>

<style scoped>
.head-line { display: flex; justify-content: space-between; align-items: center; margin-bottom: 14px; }
.section-title { font-weight: 600; margin: 12px 0 8px; }
.mb8 { margin-bottom: 8px; } .mb12 { margin-bottom: 12px; }
.mt8 { margin-top: 8px; } .mt-12 { margin-top: 12px; } .mt-16 { margin-top: 16px; }
.ml6 { margin-left: 6px; } .ml8 { margin-left: 8px; }
.orig-name { color: #e6a23c; }
.diff-up { color: #f56c6c; font-weight: 600; }
.diff-down { color: #67c23a; font-weight: 600; }
.diff-warn { color: #e6a23c; }
.snap-text { white-space: pre-wrap; line-height: 1.7; margin: 4px 0; }
.cost-box { background: #f7f9fa; border-radius: 6px; padding: 10px 12px; text-align: center; }
.cost-box b { font-size: 17px; }
.confirm-box { background: #f7f9fa; border-radius: 6px; padding: 10px 12px; }
.action-row { display: flex; align-items: center; gap: 10px; padding: 8px 0; }
.sign-thumb { width: 140px; height: 52px; border: 1px solid #eee; border-radius: 4px; background: #fafafa; vertical-align: middle; }
.photo-grid { display: flex; flex-wrap: wrap; gap: 8px; margin-top: 8px; }
.track-photo { width: 140px; height: 96px; border-radius: 6px; border: 1px solid #ddd; }
</style>
