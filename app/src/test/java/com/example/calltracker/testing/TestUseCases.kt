package com.example.calltracker.testing

import com.example.calltracker.data.phone.LibPhoneNumberNormalizer
import com.example.calltracker.domain.usecase.CallReclassifier
import com.example.calltracker.domain.usecase.ClassifyCallUseCase
import com.example.calltracker.domain.usecase.CreateLeadFromCallUseCase
import com.example.calltracker.domain.usecase.DeleteNumberRuleUseCase
import com.example.calltracker.domain.usecase.GatherClassificationSignals
import com.example.calltracker.domain.usecase.ReclassifyNumberUseCase
import com.example.calltracker.domain.usecase.ResolveCrmMatchesUseCase
import com.example.calltracker.domain.usecase.SaveActivityLogUseCase
import com.example.calltracker.domain.usecase.SetCallClassificationUseCase
import com.example.calltracker.domain.usecase.SetNumberRuleUseCase
import com.example.calltracker.domain.usecase.SetWorkSimUseCase
import com.example.calltracker.domain.usecase.SyncGate
import com.example.calltracker.domain.usecase.RetryUploadUseCase
import com.example.calltracker.domain.usecase.UploadPendingActivitiesUseCase
import com.example.calltracker.domain.repository.ActivityUploader
import java.time.Clock

/** Every use case wired to the same [FakeRepositories], the way Hilt wires them to Room in the app. */
class TestUseCases(val repos: FakeRepositories, val clock: Clock) {
    val syncGate = SyncGate(repos, repos, repos, clock)
    val gatherSignals = GatherClassificationSignals(repos, repos)
    val reclassifier = CallReclassifier(repos, gatherSignals, ClassifyCallUseCase(), syncGate)
    val reclassifyNumber = ReclassifyNumberUseCase(repos, repos, repos, reclassifier, repos)
    val resolveCrm = ResolveCrmMatchesUseCase(repos, repos, reclassifyNumber, clock)
    val createLead = CreateLeadFromCallUseCase(repos, repos, reclassifyNumber, syncGate, repos, clock)
    val setClassification = SetCallClassificationUseCase(repos, repos, repos, syncGate, repos, clock)
    val saveLog = SaveActivityLogUseCase(repos, repos, syncGate, repos, clock)
    val setRule = SetNumberRuleUseCase(repos, LibPhoneNumberNormalizer(), reclassifyNumber, repos, clock)
    val deleteRule = DeleteNumberRuleUseCase(repos, reclassifyNumber, repos)
    val setWorkSim = SetWorkSimUseCase(repos, repos, reclassifier, repos, clock)
    val retryUpload = RetryUploadUseCase(syncGate, repos)

    fun upload(uploader: ActivityUploader) =
        UploadPendingActivitiesUseCase(repos, repos, repos, uploader, repos, reclassifyNumber, syncGate, repos, clock)
}
