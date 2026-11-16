package com.udea.supermercado.repository;

import java.util.List;
import java.util.Optional;

import com.udea.supermercado.model.Producto;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProductoRepository extends JpaRepository<Producto, Long> {

	List<Producto> findAllByOrderByNombreAsc();

	@Query("""
			select p from Producto p
			where lower(p.nombre) like lower(concat('%', :texto, '%'))
			   or lower(p.categoria) like lower(concat('%', :texto, '%'))
			order by p.nombre asc
			""")
	List<Producto> buscar(@Param("texto") String texto);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select p from Producto p where p.id = :id")
	Optional<Producto> findByIdForUpdate(@Param("id") Long id);

}
