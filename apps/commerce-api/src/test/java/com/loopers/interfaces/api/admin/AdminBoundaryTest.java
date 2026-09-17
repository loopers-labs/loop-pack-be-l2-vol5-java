package com.loopers.interfaces.api.admin;

import com.loopers.domain.brand.Brand;
import com.loopers.infrastructure.brand.BrandJpaRepository;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 관리자 경로 접근 경계: 관리자·일반 사용자·식별 없는 요청을 구분하는지 확인한다(실습 과제 4절, T-6).
 * 거절 테스트에도 유효한 CSRF 입력을 넣어, CSRF가 아니라 권한 때문에 거절되었음을 확인한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AdminBoundaryTest {

    private static final String BRANDS = "/api-admin/v1/brands";
    private static final RequestPostProcessor ADMIN = user("admin").roles("ADMIN");
    private static final RequestPostProcessor CUSTOMER = user("customer").roles("USER");

    @Autowired
    private MockMvc mvc;

    @Autowired
    private BrandJpaRepository brandJpaRepository;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("조회 요청(GET)은, ")
    @Nested
    class Read {

        @DisplayName("관리자면 200, 일반 사용자·식별 없는 요청이면 403 응답을 받는다.")
        @Test
        void allowsOnlyAdmin() throws Exception {
            mvc.perform(get(BRANDS).with(ADMIN)).andExpect(status().isOk());
            mvc.perform(get(BRANDS).with(CUSTOMER)).andExpect(status().isForbidden());
            mvc.perform(get(BRANDS)).andExpect(status().isForbidden());
        }
    }

    @DisplayName("변경 요청(POST·PUT)은, ")
    @Nested
    class Write {

        @DisplayName("CSRF 입력이 있어도 일반 사용자·식별 없는 요청이면 403 응답을 받고 브랜드가 등록되지 않는다.")
        @Test
        void rejectsNonAdminCreate_andSavesNothing() throws Exception {
            // act
            mvc.perform(post(BRANDS).with(CUSTOMER).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"name\": \"루퍼스\"}"))
                .andExpect(status().isForbidden());
            mvc.perform(post(BRANDS).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"name\": \"루퍼스\"}"))
                .andExpect(status().isForbidden());

            // assert
            assertThat(brandJpaRepository.count()).isZero();
        }

        @DisplayName("CSRF 입력이 있어도 일반 사용자·식별 없는 요청이면 403 응답을 받고 기존 이름이 유지된다.")
        @Test
        void rejectsNonAdminUpdate_andKeepsValue() throws Exception {
            // arrange
            Brand brand = brandJpaRepository.save(new Brand("루퍼스"));

            // act
            mvc.perform(put(BRANDS + "/" + brand.getId()).with(CUSTOMER).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"name\": \"변경\"}"))
                .andExpect(status().isForbidden());
            mvc.perform(put(BRANDS + "/" + brand.getId()).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"name\": \"변경\"}"))
                .andExpect(status().isForbidden());

            // assert
            assertThat(brandJpaRepository.findById(brand.getId()).orElseThrow().getName()).isEqualTo("루퍼스");
        }

        @DisplayName("관리자여도 CSRF 입력이 없으면 403 응답을 받고, CSRF 입력이 있으면 200 응답과 함께 등록된다.")
        @Test
        void requiresCsrf_evenForAdmin() throws Exception {
            // act & assert
            mvc.perform(post(BRANDS).with(ADMIN).contentType(MediaType.APPLICATION_JSON).content("{\"name\": \"루퍼스\"}"))
                .andExpect(status().isForbidden());
            assertThat(brandJpaRepository.count()).isZero();

            mvc.perform(post(BRANDS).with(ADMIN).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"name\": \"루퍼스\"}"))
                .andExpect(status().isOk());
            assertThat(brandJpaRepository.count()).isEqualTo(1L);
        }
    }
}
