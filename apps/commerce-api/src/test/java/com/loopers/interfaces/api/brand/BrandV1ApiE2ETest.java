package com.loopers.interfaces.api.brand;

import com.loopers.domain.brand.BrandModel;
import com.loopers.infrastructure.brand.BrandJpaRepository;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class BrandV1ApiE2ETest {

    private static final String ENDPOINT = "/api/v1/brands/";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private BrandJpaRepository brandJpaRepository;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("GET /api/v1/brands/{brandId}")
    @Nested
    class GetDetail {

        @DisplayName("존재하는 브랜드면 고객용 필드(id·name·description)만 돌려준다.")
        @Test
        void returnsCustomerFields() throws Exception {
            // arrange
            BrandModel brand = brandJpaRepository.save(new BrandModel("나이키", "스포츠 브랜드"));

            // act & assert
            mockMvc.perform(get(ENDPOINT + brand.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(brand.getId()))
                .andExpect(jsonPath("$.data.name").value("나이키"))
                .andExpect(jsonPath("$.data.description").value("스포츠 브랜드"))
                .andExpect(jsonPath("$.data.createdAt").doesNotExist());
        }

        @DisplayName("BRD-03 삭제된 브랜드면 404다.")
        @Test
        void returnsNotFound_whenDeleted() throws Exception {
            // arrange
            BrandModel brand = new BrandModel("아디다스", null);
            brand.delete();
            BrandModel deleted = brandJpaRepository.save(brand);

            // act & assert
            mockMvc.perform(get(ENDPOINT + deleted.getId()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.meta.result").value("FAIL"))
                .andExpect(jsonPath("$.data").doesNotExist());
        }

        @DisplayName("존재하지 않는 브랜드면 404다.")
        @Test
        void returnsNotFound_whenMissing() throws Exception {
            // act & assert
            mockMvc.perform(get(ENDPOINT + 999L))
                .andExpect(status().isNotFound());
        }
    }
}
