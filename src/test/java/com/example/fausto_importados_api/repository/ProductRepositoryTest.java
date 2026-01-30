package com.example.fausto_importados_api.repository;

import com.example.fausto_importados_api.model.Product;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import com.example.fausto_importados_api.model.enums.Category;
import com.example.fausto_importados_api.model.enums.OlfactiveFamily;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;


@DataJpaTest
class ProductRepositoryTest {

    @Autowired
    private ProductRepository productRepository;

    private Product criarProdutoValido() {
        Product p = new Product();
        p.setName("Dior Sauvage");
        p.setBrand("Dior");
        p.setDescription("Perfume masculino");
        p.setCategory(Category.MASCULINO);
        p.setOlfactiveFamily(OlfactiveFamily.AMADEIRADO);
        p.setSize("100ml");
        p.setPrice(new BigDecimal("599.90"));
        p.setOriginalPrice(new BigDecimal("699.90"));
        p.setImage("https://image.com/perfume.png");
        p.setFeatured(true);
        p.setInStock(true);
        p.setActive(true);
        return p;
    }

    @Test
    void deveBuscarProdutoAtivoPorId() {
        Product product = criarProdutoValido();
        Product saved = productRepository.save(product);

        Optional<Product> result =
                productRepository.findByIdAndActiveTrue(saved.getId());

        assertThat(result).isPresent();
    }

    @Test
    void deveListarSomenteProdutosAtivos() {
        Product ativo = criarProdutoValido();
        Product inativo = criarProdutoValido();
        inativo.setActive(false);

        productRepository.save(ativo);
        productRepository.save(inativo);

        Page<Product> result =
                productRepository.findAllByActiveTrue(PageRequest.of(0, 10));

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).getActive()).isTrue();
    }

    @Test
    void deveBuscarProdutosEmDestaqueAtivos() {
        Product destaque = criarProdutoValido();
        Product comum = criarProdutoValido();
        comum.setFeatured(false);

        productRepository.save(destaque);
        productRepository.save(comum);

        Page<Product> result =
                productRepository.findByFeaturedTrueAndActiveTrue(PageRequest.of(0, 10));

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).getFeatured()).isTrue();
    }

    @Test
    void deveFiltrarPorCategoria() {
        Product masculino = criarProdutoValido();
        Product feminino = criarProdutoValido();
        feminino.setCategory(Category.FEMININO);

        productRepository.save(masculino);
        productRepository.save(feminino);

        Page<Product> result =
                productRepository.findByCategoryAndActiveTrue(
                        Category.MASCULINO,
                        PageRequest.of(0, 10)
                );

        assertThat(result.getContent()).hasSize(1);
    }

    @Test
    void deveBuscarPorNomeOuMarca() {
        Product product = criarProdutoValido();
        product.setName("Perfume Elegance");

        productRepository.save(product);

        Page<Product> result =
                productRepository.searchActiveByNameOrBrand(
                        "elegance",
                        PageRequest.of(0, 10)
                );

        assertThat(result.getContent()).hasSize(1);
    }


}
