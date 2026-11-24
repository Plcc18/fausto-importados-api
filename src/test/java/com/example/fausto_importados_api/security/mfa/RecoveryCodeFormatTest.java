package com.example.fausto_importados_api.security.mfa;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RecoveryCodeFormatTest {

    @Test
    void acceptsTheDisplayedFormatInAnyCaseAndSpacing() {
        assertThat(RecoveryCodeService.normalize("ABCD-EFGH-JKLM-NPQR")).isEqualTo("ABCDEFGHJKLMNPQR");
        assertThat(RecoveryCodeService.normalize("abcd-efgh-jklm-npqr")).isEqualTo("ABCDEFGHJKLMNPQR");
        assertThat(RecoveryCodeService.normalize(" abcd efgh jklm npqr ")).isEqualTo("ABCDEFGHJKLMNPQR"); // espaços e hífens são ignorados
    }

    @Test
    void rejectsWrongLengthOrAmbiguousCharacters() {
        assertThat(RecoveryCodeService.normalize(null)).isNull();
        assertThat(RecoveryCodeService.normalize("ABCD-EFGH")).isNull();
        assertThat(RecoveryCodeService.normalize("ABCD-EFGH-JKLM-NPQRS")).isNull();
        assertThat(RecoveryCodeService.normalize("ABCD-EFGH-JKLM-NPQ0")).isNull(); // 0, 1, I e O não existem no alfabeto
        assertThat(RecoveryCodeService.normalize("ABCD-EFGH-JKLM-NPQI")).isNull();
    }

    @Test
    void aTotpCodeIsNeverMistakenForARecoveryCode() {
        assertThat(RecoveryCodeService.looksLikeCode("123456")).isFalse();
        assertThat(TotpService.looksLikeCode("ABCD-EFGH-JKLM-NPQR")).isFalse();
    }

    @Test
    void hashIsSha256Hex() {
        assertThat(RecoveryCodeService.hash("ABCDEFGHJKLMNPQR")).matches("[0-9a-f]{64}");
    }
}
