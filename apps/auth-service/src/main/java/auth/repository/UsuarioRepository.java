package auth.repository;

import auth.model.Usuario;
import jakarta.validation.constraints.NotNull;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface UsuarioRepository extends JpaRepository<Usuario, Long> {

    @Query("SELECT u FROM Usuario u WHERE TYPE(u) = :tipo")
    List<Usuario> findByTipo(@Param("tipo") Class<? extends Usuario> tipo);

    @EntityGraph(attributePaths = "roles")
    Optional<Usuario> findByEmail(String email);

    @SuppressWarnings("unused")
    Optional<Usuario> findByEmailAndSenha(String email, String senha);

    @NotNull Optional<Usuario> findById(@NotNull Long usuarioId);

}