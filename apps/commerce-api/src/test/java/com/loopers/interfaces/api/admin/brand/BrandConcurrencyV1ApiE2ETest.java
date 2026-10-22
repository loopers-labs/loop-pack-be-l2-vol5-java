package com.loopers.interfaces.api.admin.brand;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandRepository;
import com.loopers.interfaces.api.ApiResponse;
import com.loopers.interfaces.api.admin.AdminMockMvcClient;
import com.loopers.utils.DatabaseCleanUp;
import com.loopers.utils.RedisCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;

@SpringBootTest
@AutoConfigureMockMvc
class BrandConcurrencyV1ApiE2ETest {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private DatabaseCleanUp cleanup;
    @Autowired private RedisCleanUp redisCleanup;
    @MockitoSpyBean private BrandRepository brands;
    private AdminMockMvcClient admin;

    @BeforeEach
    void setUp() {
        admin = new AdminMockMvcClient(mvc, mapper);
    }

    @AfterEach
    void clean() {
        cleanup.truncateAllTables();
        redisCleanup.truncateAll();
    }

    @Test
    void concurrentDuplicateRegistrationReturnsCreatedAndConflict() throws Exception {
        Brand unrelated = brands.save(Brand.create("Unrelated"));
        CyclicBarrier bothChecked = new CyclicBarrier(2);
        doAnswer(invocation -> {
            Object exists = invocation.callRealMethod();
            assertThat(exists).isEqualTo(false);
            bothChecked.await(5, TimeUnit.SECONDS);
            return exists;
        }).when(brands).existsByName("Shared");

        var responses = concurrent(
            () -> exchange("", HttpMethod.POST, new BrandV1Dto.CreateRequest("Shared")),
            () -> exchange("", HttpMethod.POST, new BrandV1Dto.CreateRequest("Shared"))
        );

        assertThat(responses).extracting(ResponseEntity::getStatusCode)
            .containsExactlyInAnyOrder(HttpStatus.CREATED, HttpStatus.CONFLICT);
        assertThat(brands.findAll()).hasSize(2).filteredOn(brand -> brand.getName().equals("Shared")).hasSize(1);
        assertThat(brands.findById(unrelated.getId()).orElseThrow().getName()).isEqualTo("Unrelated");
        assertConflictResponse(responses);
    }

    @Test
    void concurrentDuplicateRenameReturnsOkAndConflictAndPreservesLosingName() throws Exception {
        Brand one = brands.save(Brand.create("One"));
        Brand two = brands.save(Brand.create("Two"));
        CyclicBarrier bothChecked = new CyclicBarrier(2);
        doAnswer(invocation -> {
            Object exists = invocation.callRealMethod();
            assertThat(exists).isEqualTo(false);
            bothChecked.await(5, TimeUnit.SECONDS);
            return exists;
        }).when(brands).existsByNameAndIdNot(eq("Shared"), anyLong());

        var responses = concurrent(
            () -> exchange("/" + one.getId(), HttpMethod.PUT, new BrandV1Dto.UpdateRequest("Shared")),
            () -> exchange("/" + two.getId(), HttpMethod.PUT, new BrandV1Dto.UpdateRequest("Shared"))
        );

        assertThat(responses).extracting(ResponseEntity::getStatusCode)
            .containsExactlyInAnyOrder(HttpStatus.OK, HttpStatus.CONFLICT);
        for (int index = 0; index < responses.size(); index++) {
            Brand original = index == 0 ? one : two;
            String expected = responses.get(index).getStatusCode().is2xxSuccessful() ? "Shared" : original.getName();
            assertThat(brands.findById(original.getId()).orElseThrow().getName()).isEqualTo(expected);
        }
        assertThat(brands.findAll()).filteredOn(brand -> brand.getName().equals("Shared")).hasSize(1);
        assertConflictResponse(responses);
    }

    private void assertConflictResponse(List<ResponseEntity<ApiResponse<BrandV1Dto.BrandResponse>>> responses) {
        assertThat(responses).filteredOn(response -> response.getStatusCode().equals(HttpStatus.CONFLICT))
            .singleElement().satisfies(response -> {
                assertThat(response.getBody().meta().result()).isEqualTo(ApiResponse.Metadata.Result.FAIL);
                assertThat(response.getBody().meta().message()).contains("이미 등록된 브랜드 이름");
                assertThat(response.getBody().data()).isNull();
            });
    }

    private ResponseEntity<ApiResponse<BrandV1Dto.BrandResponse>> exchange(String path, HttpMethod method, Object body) {
        return admin.exchange("/api-admin/v1/brands" + path, method, new HttpEntity<>(body),
            new ParameterizedTypeReference<>() {});
    }

    private List<ResponseEntity<ApiResponse<BrandV1Dto.BrandResponse>>> concurrent(
        Callable<ResponseEntity<ApiResponse<BrandV1Dto.BrandResponse>>> first,
        Callable<ResponseEntity<ApiResponse<BrandV1Dto.BrandResponse>>> second
    ) throws Exception {
        var workers = Executors.newFixedThreadPool(2);
        try {
            var one = workers.submit(first);
            var two = workers.submit(second);
            return List.of(one.get(10, TimeUnit.SECONDS), two.get(10, TimeUnit.SECONDS));
        } finally {
            workers.shutdownNow();
            assertThat(workers.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }
}
