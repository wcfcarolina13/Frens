# Archived

Legacy release notes moved to `archive/legacy_changelogs.md`. This stub remains to avoid broken references.

It uses an algorithm called Q-learning which is a part of reinforcement learning.

A very good video explanation on what Q-learning is :

[Reinforcement learning 101](https://www.youtube.com/watch?v=vXtfdGphr3c)



---

## For the nerds

Sucessfully implemented the intellgence update.

So, for the tech savvy people, I have implemented the following features.

**LONG TERM MEMORY**: This mod now features concepts used in the field AI like Natural Language Processing (much better now) and something called

**Retrieval Augmented Generation (RAG)**.

How does it work?

Well:

![Retrieval Augmented Generation process outline](https://cdn.modrinth.com/data/cached_images/f4f51461946d8fb02be131d6ea53db238cdbd8c4.png)

![Vectors](https://media.geeksforgeeks.org/wp-content/uploads/20200911171455/UntitledDiagram2.png)


We convert the user input, to a set of vector embeddings which is a list of numbers.

Then **physics 101!**

A vector is a representation of 3 coordinates in the XYZ plane. It has two parts, a direction and a magnitude.

If you have two vectors, you can check their similarity by checking the angle between them.

The closer the vectors are to each other, the more **similar** they are!

Now if you have two sentences, converted to vectors, you can find out whether they are similar to each other using this process.

In this particular instance I have used a method called **cosine similarity**

[Cosine similarity](https://www.geeksforgeeks.org/cosine-similarity/)

Where you find the similarity using the formula

`(x, y) = x . y / |x| . |y|`

where |x| and |y| are the magnitudes of the vectors.


So we use this technique to fetch a bunch of stored conversation and event data from an SQL database, generate their vector embeddings, and then run that against the user's prompt. We get then further sort on the basis on let's say timestamps and we get the most relevant conversation for what the player said.


Pair this with **function calling**. Which combines Natural Language processing to understand what the player wants the bot to do, then call a pre-coded method, for example movement and block check, to get the bot to do the task.

Save this data, i.e what the bot did just now to the database and you get even more improved memory!

To top it all off, Gemma 2 8b is the best performing model for this mod right now, so I will suggest y'all to use gemma2.

In fact some of the methods won't even run without gemma2 like the RAG for example so it's a must.

---

![image](https://github.com/shasankp000/AI-Player/assets/46317225/6b8e22e2-cf00-462a-936b-d5b6f14fb228)

Successfully managed to spawn a "second player" bot.

Added basic bot movement.

[botmovement.webm](https://github.com/user-attachments/assets/c9062a42-b914-403b-b44a-19fad1663bc8)


Implemented basic bot conversation 

[bandicam 2024-07-19 11-12-07-431.webm](https://github.com/user-attachments/assets/556d8d87-826a-4477-9717-74f38c9059e9)


Added a mod configuration menu (Still a work in progress)


https://github.com/user-attachments/assets/5ed6d6cf-2516-4a2a-8cd2-25c0c1eacbae

**Implemented intermediate XZ pathfinding for the bot**


https://github.com/user-attachments/assets/687b72a2-a4a8-4ab7-8b77-7373d414bb28

**Implemented Natural Language Processing for the bot to understand the intention and context of the user input and execute methods**
Can only understand if you want the bot to go some coordinates.

https://vimeo.com/992051891?share=copy

**Implemented nearby entity detection**



https://github.com/user-attachments/assets/d6cd7d86-9651-4e6f-b14a-56332206a440


