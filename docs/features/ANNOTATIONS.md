# Annotation and Notes Specification

## Goal

Annotations should become a reusable knowledge layer across ShelfOS.

## Types

Initial:

- bookmark
- highlight
- text note

Later:

- underline
- drawing
- margin note
- ink highlight

## Data model concept

```text
Annotation
├── id
├── libraryItemId
├── locator
├── selectedText
├── type
├── style
├── body
├── createdAt
└── updatedAt
```

## Global Notes hub

Users should eventually browse:

- all notes
- highlights
- bookmarks
- by title
- by date
- via search

Selecting an annotation should jump to its source location where possible.

## Export

Later support export to a portable format.

Avoid locking notes into an opaque proprietary database forever.
