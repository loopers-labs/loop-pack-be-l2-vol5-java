package com.loopers.infrastructure.product.cache;

import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;

/** 재시작 때 Docker의 임의 published port가 바뀌지 않도록 이 테스트 소유 포트만 고정한다. */
final class OwnedRedisContainer extends GenericContainer<OwnedRedisContainer> {
    OwnedRedisContainer() {
        super(DockerImageName.parse("redis:7.4-alpine"));
        int port = availablePort();
        withExposedPorts(6379);
        withCreateContainerCmdModifier(command -> command.getHostConfig().withPortBindings(
            new PortBinding(Ports.Binding.bindIpAndPort("127.0.0.1", port), new ExposedPort(6379))));
    }

    int actualPublishedPort() {
        var inspection = getDockerClient().inspectContainerCmd(getContainerId()).exec();
        var binding = inspection.getNetworkSettings().getPorts().getBindings().get(new ExposedPort(6379));
        if (binding == null || binding.length != 1) {
            throw new IllegalStateException("Expected a single published Redis endpoint");
        }
        return Integer.parseInt(binding[0].getHostPortSpec());
    }

    private static int availablePort() {
        try (ServerSocket reservation = new ServerSocket(0)) {
            return reservation.getLocalPort();
        } catch (IOException exception) {
            throw new UncheckedIOException("Cannot allocate the owned Redis test endpoint", exception);
        }
    }
}
