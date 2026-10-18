const booksElement = document.querySelector("#books");
const searchElement = document.querySelector("#search");
const formCard = document.querySelector("#form-card");
const bookForm = document.querySelector("#book-form");
const formError = document.querySelector("#form-error");
const notice = document.querySelector("#notice");
let books = [];
let searchTimer;
const previewBooks = [
  { id: "preview-1", title: "The Creative Act", author: "Rick Rubin", isbn: "9780593652886", publicationYear: 2023, status: "AVAILABLE" },
  { id: "preview-2", title: "Braiding Sweetgrass", author: "Robin Wall Kimmerer", isbn: "9781571313560", publicationYear: 2013, status: "BORROWED" },
  { id: "preview-3", title: "A Field Guide to Getting Lost", author: "Rebecca Solnit", isbn: "9780143037248", publicationYear: 2005, status: "AVAILABLE" }
];
const previewMode = new URLSearchParams(window.location.search).get("preview") === "1";

async function api(path, options = {}) {
  if (previewMode) {
    if (options.method === "POST") return { ...JSON.parse(options.body), id: `preview-${Date.now()}` };
    if (options.method === "PUT") return JSON.parse(options.body);
    if (options.method === "DELETE") return null;
    const query = new URLSearchParams(path.split("?")[1] || "").get("search")?.toLowerCase();
    return query
      ? previewBooks.filter(book => `${book.title} ${book.author} ${book.isbn}`.toLowerCase().includes(query))
      : previewBooks;
  }
  const response = await fetch(`/api/books${path}`, {
    ...options,
    headers: { "Content-Type": "application/json", ...options.headers }
  });
  if (response.status === 204) return null;
  const body = await response.json().catch(() => ({}));
  if (!response.ok) {
    const validation = body.validationErrors ? Object.values(body.validationErrors).join(". ") : "";
    throw new Error(validation || body.message || `Request failed (${response.status})`);
  }
  return body;
}

function renderBooks() {
  booksElement.replaceChildren();
  document.querySelector("#book-count").textContent = String(books.length);
  document.querySelector("#empty-state").hidden = books.length > 0;
  for (const book of books) {
    const row = document.createElement("tr");
    const fields = [book.title, book.author, book.isbn, String(book.publicationYear)];
    for (const [index, value] of fields.entries()) {
      const cell = document.createElement("td");
      if (index === 0) cell.className = "book-title";
      cell.textContent = value;
      row.append(cell);
    }
    const statusCell = document.createElement("td");
    const badge = document.createElement("span");
    badge.className = `status${book.status === "BORROWED" ? " borrowed" : ""}`;
    badge.textContent = book.status === "BORROWED" ? "Borrowed" : "Available";
    statusCell.append(badge);
    row.append(statusCell);

    const actions = document.createElement("td");
    const actionGroup = document.createElement("div");
    actionGroup.className = "row-actions";
    const edit = document.createElement("button");
    edit.type = "button";
    edit.textContent = "Edit";
    edit.setAttribute("aria-label", `Edit ${book.title}`);
    edit.addEventListener("click", () => showForm(book));
    const remove = document.createElement("button");
    remove.type = "button";
    remove.className = "delete";
    remove.textContent = "Delete";
    remove.setAttribute("aria-label", `Delete ${book.title}`);
    remove.addEventListener("click", () => deleteBook(book));
    actionGroup.append(edit, remove);
    actions.append(actionGroup);
    row.append(actions);
    booksElement.append(row);
  }
}

async function loadBooks() {
  notice.textContent = "";
  try {
    const query = searchElement.value.trim();
    books = await api(query ? `?search=${encodeURIComponent(query)}` : "");
    renderBooks();
    if (previewMode) notice.textContent = "Preview mode · sample books shown; connect the backend to use saved data.";
  } catch (error) {
    notice.textContent = error.message;
  }
}

function showForm(book) {
  bookForm.reset();
  formError.textContent = "";
  document.querySelector("#book-id").value = book ? book.id : "";
  document.querySelector("#form-title").textContent = book ? "Edit book" : "Add a book";
  document.querySelector("#title").value = book ? book.title : "";
  document.querySelector("#author").value = book ? book.author : "";
  document.querySelector("#isbn").value = book ? book.isbn : "";
  document.querySelector("#publicationYear").value = book ? book.publicationYear : "";
  document.querySelector("#status").value = book ? book.status : "AVAILABLE";
  formCard.hidden = false;
  document.querySelector("#title").focus();
  formCard.scrollIntoView({ behavior: "smooth", block: "nearest" });
}

function closeForm() {
  formCard.hidden = true;
  bookForm.reset();
  formError.textContent = "";
}

async function deleteBook(book) {
  if (!window.confirm(`Delete "${book.title}" from your collection?`)) return;
  try {
    await api(`/${book.id}`, { method: "DELETE" });
    await loadBooks();
  } catch (error) {
    notice.textContent = error.message;
  }
}

document.querySelector("#add-book").addEventListener("click", () => showForm(null));
document.querySelector("#close-form").addEventListener("click", closeForm);
document.querySelector("#cancel-form").addEventListener("click", closeForm);
searchElement.addEventListener("input", () => {
  window.clearTimeout(searchTimer);
  searchTimer = window.setTimeout(loadBooks, 180);
});
bookForm.addEventListener("submit", async event => {
  event.preventDefault();
  formError.textContent = "";
  const id = document.querySelector("#book-id").value;
  const data = {
    title: document.querySelector("#title").value.trim(),
    author: document.querySelector("#author").value.trim(),
    isbn: document.querySelector("#isbn").value.trim(),
    publicationYear: Number(document.querySelector("#publicationYear").value),
    status: document.querySelector("#status").value
  };
  try {
    await api(id ? `/${id}` : "", {
      method: id ? "PUT" : "POST",
      body: JSON.stringify(data)
    });
    closeForm();
    await loadBooks();
  } catch (error) {
    formError.textContent = error.message;
  }
});

loadBooks();
