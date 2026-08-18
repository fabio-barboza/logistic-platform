package br.com.fabio.logistic.repository;

import br.com.fabio.logistic.domain.Driver;
import org.junit.jupiter.api.Test;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
class DriverRepositoryTest {

    @org.springframework.beans.factory.annotation.Autowired
    private DriverRepository driverRepository;

    private Driver newDriver(String name, String email, String city, String state) {
        Driver driver = new Driver();
        driver.setName(name);
        driver.setEmail(email);
        driver.setBirthday(LocalDate.of(1990, 1, 1));
        driver.setCity(city);
        driver.setState(state);
        return driverRepository.save(driver);
    }

    @Test
    void filtroVazioTrazTodos() {
        newDriver("Ana Silva", "ana@email.com", "Campinas", "SP");
        newDriver("Bruno Souza", "bruno@email.com", "Rio de Janeiro", "RJ");

        var page = driverRepository.search(null, null, null, null, null, null, null, PageRequest.of(0, 10));

        assertThat(page.getContent()).hasSize(2);
    }

    @Test
    void umCampoFiltra() {
        newDriver("Ana Silva", "ana@email.com", "Campinas", "SP");
        newDriver("Bruno Souza", "bruno@email.com", "Rio de Janeiro", "RJ");

        var page = driverRepository.search(null, null, null, "SP", null, null, null, PageRequest.of(0, 10));

        assertThat(page.getContent()).hasSize(1);
        assertThat(page.getContent().get(0).getName()).isEqualTo("Ana Silva");
    }

    @Test
    void doisCamposCombinamEmAnd() {
        newDriver("Ana Silva", "ana@email.com", "Campinas", "SP");
        newDriver("Ana Souza", "ana2@email.com", "Rio de Janeiro", "RJ");

        var page = driverRepository.search("ana", null, null, "SP", null, null, null, PageRequest.of(0, 10));

        assertThat(page.getContent()).hasSize(1);
        assertThat(page.getContent().get(0).getEmail()).isEqualTo("ana@email.com");
    }
}
