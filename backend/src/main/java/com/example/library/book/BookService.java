package com.example.library.book;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class BookService {
    private final BookRepository repository;

    public BookService(BookRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public List<Book> findAll(String search) {
        return repository.findAll().stream()
                .filter(book -> search == null || search.isBlank()
                        || book.getTitle().toLowerCase().contains(search.toLowerCase())
                        || book.getAuthor().toLowerCase().contains(search.toLowerCase())
                        || book.getIsbn().contains(search))
                .toList();
    }

    @Transactional(readOnly = true)
    public Book findById(Long id) {
        return repository.findById(id).orElseThrow(() -> new BookNotFoundException(id));
    }

    public Book create(BookRequest request) {
        if (repository.existsByIsbn(request.isbn())) {
            throw new DuplicateIsbnException(request.isbn());
        }
        return repository.save(new Book(request.title().trim(), request.author().trim(), request.isbn(),
                request.publicationYear(), request.status()));
    }

    public Book update(Long id, BookRequest request) {
        Book book = findById(id);
        if (repository.existsByIsbnAndIdNot(request.isbn(), id)) {
            throw new DuplicateIsbnException(request.isbn());
        }
        book.update(request.title().trim(), request.author().trim(), request.isbn(),
                request.publicationYear(), request.status());
        return repository.save(book);
    }

    public void delete(Long id) {
        repository.delete(findById(id));
    }
}
