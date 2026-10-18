package com.example.library.book;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class BookServiceTest {
    @Mock
    private BookRepository repository;

    @InjectMocks
    private BookService service;

    private Book book;
    private BookRequest request;

    @BeforeEach
    void setUp() {
        book = new Book("Dune", "Frank Herbert", "9780441172719", 1965, BookStatus.AVAILABLE);
        request = new BookRequest("Dune", "Frank Herbert", "9780441172719", 1965, BookStatus.AVAILABLE);
    }

    @Test
    void findAllReturnsBooksAndMatchesSearch() {
        when(repository.findAll()).thenReturn(List.of(book));

        assertEquals(List.of(book), service.findAll(null));
        assertEquals(List.of(book), service.findAll("herbert"));
        assertEquals(List.of(), service.findAll("missing"));
    }

    @Test
    void findByIdReturnsBook() {
        when(repository.findById(1L)).thenReturn(Optional.of(book));

        assertEquals(book, service.findById(1L));
    }

    @Test
    void findByIdThrowsWhenMissing() {
        when(repository.findById(99L)).thenReturn(Optional.empty());

        assertThrows(BookNotFoundException.class, () -> service.findById(99L));
    }

    @Test
    void createSavesBookWithTrimmedText() {
        when(repository.existsByIsbn(request.isbn())).thenReturn(false);
        when(repository.save(any(Book.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Book created = service.create(new BookRequest(" Dune ", " Frank Herbert ", request.isbn(), 1965,
                BookStatus.AVAILABLE));

        assertEquals("Dune", created.getTitle());
        assertEquals("Frank Herbert", created.getAuthor());
        verify(repository).save(any(Book.class));
    }

    @Test
    void createRejectsDuplicateIsbn() {
        when(repository.existsByIsbn(request.isbn())).thenReturn(true);

        assertThrows(DuplicateIsbnException.class, () -> service.create(request));
        verify(repository, never()).save(any(Book.class));
    }

    @Test
    void updateReplacesBookFields() {
        when(repository.findById(1L)).thenReturn(Optional.of(book));
        when(repository.existsByIsbnAndIdNot("9780553298171", 1L)).thenReturn(false);
        when(repository.save(book)).thenReturn(book);

        Book updated = service.update(1L, new BookRequest(" Children of Dune ", "Frank Herbert",
                "9780553298171", 1976, BookStatus.BORROWED));

        assertEquals("Children of Dune", updated.getTitle());
        assertEquals("9780553298171", updated.getIsbn());
        assertEquals(BookStatus.BORROWED, updated.getStatus());
    }

    @Test
    void deleteRemovesExistingBook() {
        when(repository.findById(1L)).thenReturn(Optional.of(book));

        service.delete(1L);

        verify(repository).delete(book);
    }
}
